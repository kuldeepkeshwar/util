/*
 * Copyright 2010 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.twitter.logging

import java.io.IOException
import java.net._
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.charset.StandardCharsets.ISO_8859_1
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{ArrayBlockingQueue, LinkedBlockingQueue, ThreadPoolExecutor, TimeUnit}
import java.util.{Arrays, logging => javalog}
import com.twitter.concurrent.NamedPoolThreadFactory
import com.twitter.conversions.StringOps._
import com.twitter.conversions.DurationOps._
import com.twitter.finagle.stats.{NullStatsReceiver, StatsReceiver}
import com.twitter.util.{Duration, Stopwatch, Time}
import scala.util.control.NonFatal

object ScribeHandler {
  private sealed trait ServerType
  private case object Unknown extends ServerType
  private case object Archaic extends ServerType
  private case object Modern extends ServerType

  val OK = 0
  val TRY_LATER = 1

  val DefaultHostname = "localhost"
  val DefaultPort = 1463
  val DefaultCategory = "scala"
  val DefaultBufferTime = 100.milliseconds
  val DefaultConnectBackoff = 15.seconds
  val DefaultMaxMessagesPerTransaction = 1000
  val DefaultMaxMessagesToBuffer = 10000
  val DefaultStatsReportPeriod = 5.minutes
  val log = Logger.get(getClass)

  /**
   * Generates a HandlerFactory that returns a ScribeHandler.
   *
   * @note ScribeHandler is usually used to write structured binary data.
   * When used in this way, wrapping this in other handlers, such as ThrottledHandler,
   * which emit plain-text messages into the log, will corrupt the resulting data.
   *
   * @note Java users of this library can use one of the methods in [[ScribeHandlers]],
   * so they need not provide all the default arguments.
   *
   * @param bufferTime
   * send a scribe message no more frequently than this:
   *
   * @param connectBackoff
   * don't connect more frequently than this (when the scribe server is down):
   */
  def apply(
    hostname: String = DefaultHostname,
    port: Int = DefaultPort,
    category: String = DefaultCategory,
    bufferTime: Duration = DefaultBufferTime,
    connectBackoff: Duration = DefaultConnectBackoff,
    maxMessagesPerTransaction: Int = DefaultMaxMessagesPerTransaction,
    maxMessagesToBuffer: Int = DefaultMaxMessagesToBuffer,
    formatter: Formatter = new Formatter(),
    level: Option[Level] = None,
    statsReceiver: StatsReceiver = NullStatsReceiver
  ): () => ScribeHandler =
    () =>
      new ScribeHandler(
        hostname,
        port,
        category,
        bufferTime,
        connectBackoff,
        maxMessagesPerTransaction,
        maxMessagesToBuffer,
        formatter,
        level,
        statsReceiver
    )

  def apply(
    hostname: String,
    port: Int,
    category: String,
    bufferTime: Duration,
    connectBackoff: Duration,
    maxMessagesPerTransaction: Int,
    maxMessagesToBuffer: Int,
    formatter: Formatter,
    level: Option[Level]
  ): () => ScribeHandler =
    apply(
      hostname,
      port,
      category,
      bufferTime,
      connectBackoff,
      maxMessagesPerTransaction,
      maxMessagesToBuffer,
      formatter,
      level,
      NullStatsReceiver
    )

  private[logging] val ScribePrefix: Array[Byte] = Array[Byte](
    // version 1, call, "Log", reqid=0
    0x80.toByte,
    1,
    0,
    1,
    0,
    0,
    0,
    3,
    'L'.toByte,
    'o'.toByte,
    'g'.toByte,
    0,
    0,
    0,
    0,
    // list of structs
    15,
    0,
    1,
    12
  )
  private[logging] val OldScribePrefix: Array[Byte] = Array[Byte](
    // (no version), "Log", reply, reqid=0
    0,
    0,
    0,
    3,
    'L'.toByte,
    'o'.toByte,
    'g'.toByte,
    1,
    0,
    0,
    0,
    0,
    // list of structs
    15,
    0,
    1,
    12
  )

  private[logging] val ScribeReply: Array[Byte] = Array[Byte](
    // version 1, reply, "Log", reqid=0
    0x80.toByte,
    1,
    0,
    2,
    0,
    0,
    0,
    3,
    'L'.toByte,
    'o'.toByte,
    'g'.toByte,
    0,
    0,
    0,
    0,
    // int, fid 0, 0=ok
    8,
    0,
    0,
    0,
    0,
    0,
    0,
    0
  )
  private[logging] val OldScribeReply: Array[Byte] = Array[Byte](
    0,
    0,
    0,
    20,
    // (no version), "Log", reply, reqid=0
    0,
    0,
    0,
    3,
    'L'.toByte,
    'o'.toByte,
    'g'.toByte,
    2,
    0,
    0,
    0,
    0,
    // int, fid 0, 0=ok
    8,
    0,
    0,
    0,
    0,
    0,
    0,
    0
  )
}

/**
 * NOTE: ScribeHandler is usually used to write structured binary data.
 * When used in this way, wrapping this in other handlers, such as ThrottledHandler,
 * which emit plain-text messages into the log, will corrupt the resulting data.
 */
class ScribeHandler(
  hostname: String,
  port: Int,
  category: String,
  bufferTime: Duration,
  connectBackoff: Duration,
  maxMessagesPerTransaction: Int,
  maxMessagesToBuffer: Int,
  formatter: Formatter,
  level: Option[Level],
  statsReceiver: StatsReceiver)
    extends Handler(formatter, level) {
  import ScribeHandler._

  def this(
    hostname: String,
    port: Int,
    category: String,
    bufferTime: Duration,
    connectBackoff: Duration,
    maxMessagesPerTransaction: Int,
    maxMessagesToBuffer: Int,
    formatter: Formatter,
    level: Option[Level]
  ) =
    this(
      hostname,
      port,
      category,
      bufferTime,
      connectBackoff,
      maxMessagesPerTransaction,
      maxMessagesToBuffer,
      formatter,
      level,
      NullStatsReceiver
    )

  private[this] val stats = new ScribeHandlerStats(statsReceiver)

  // it may be necessary to log errors here if scribe is down:
  private val loggerName = getClass.getName

  private var lastConnectAttempt = Time.epoch

  @volatile private var _lastTransmission = Time.epoch
  // visible for testing
  private[logging] def updateLastTransmission(): Unit = _lastTransmission = Time.now

  private var socket: Option[Socket] = None
  // visible for testing
  private[logging] def setSocket(newSocket: Option[Socket]) = synchronized { socket = newSocket }

  private var serverType: ServerType = Unknown

  private def isArchaicServer() = { serverType == Archaic }

  // Could be rewritten using a simple Condition (await/notify) or producer/consumer
  // with timed batching
  private[logging] val flusher = {
    val threadFactory = new NamedPoolThreadFactory("ScribeFlusher-" + category, true)
    // should be 1, but this is a crude form of retry
    val queue = new ArrayBlockingQueue[Runnable](5)
    val rejectionHandler = new ThreadPoolExecutor.DiscardPolicy()
    new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, queue, threadFactory, rejectionHandler)
  }

  private[logging] val queue = new LinkedBlockingQueue[Array[Byte]](maxMessagesToBuffer)

  def queueSize: Int = queue.size()

  override def flush(): Unit = {
    flusher.execute(new Runnable {
      def run(): Unit = { sendBatch() }
    })
  }

  private def connect(): Unit = {
    if (!socket.isDefined) {
      if (Time.now.since(lastConnectAttempt) > connectBackoff) {
        try {
          lastConnectAttempt = Time.now
          socket = Some(new Socket(hostname, port))

          stats.incrConnection()
          serverType = Unknown
        } catch {
          case e: Exception =>
            log.error("Unable to open socket to scribe server at %s:%d: %s", hostname, port, e)
            stats.incrConnectionFailure()
        }
      } else {
        stats.incrConnectionSkipped()
      }
    }
  }

  private def detectArchaicServer(): Unit = {
    if (serverType != Unknown) return

    serverType = socket match {
      case None => Unknown
      case Some(s) => {
        val outStream = s.getOutputStream()

        try {
          val fakeMessageWithOldScribePrefix: Array[Byte] = {
            val prefix = OldScribePrefix
            val messageSize = prefix.length + 5
            val buffer = ByteBuffer.wrap(new Array[Byte](messageSize + 4))
            buffer.order(ByteOrder.BIG_ENDIAN)
            buffer.putInt(messageSize)
            buffer.put(prefix)
            buffer.putInt(0)
            buffer.put(0: Byte)
            buffer.array
          }

          outStream.write(fakeMessageWithOldScribePrefix)
          readResponseExpecting(s, OldScribeReply)

          // Didn't get exception, so the server must be archaic.
          log.debug("Scribe server is archaic; changing to old protocol for future requests.")
          Archaic
        } catch {
          case NonFatal(_) => Modern
        }
      }
    }
  }

  // TODO we should send any remaining messages before the app terminates
  // visible for tests
  private[logging] def sendBatch(): Unit = {
    synchronized {
      connect()
      detectArchaicServer()
      socket.foreach { s =>
        val outStream = s.getOutputStream()
        var remaining = queue.size
        // try to send the log records in batch
        // need to check if socket is closed due to exception
        while (remaining > 0 && socket.isDefined) {
          val count = maxMessagesPerTransaction min remaining
          val buffer = makeBuffer(count)
          stats.statBatchSize(count, buffer.capacity)

          val elapsed = Stopwatch.start()
          try {
            outStream.write(buffer.array)
            val expectedReply = if (isArchaicServer()) OldScribeReply else ScribeReply

            readResponseExpecting(s, expectedReply)

            stats.sendRecordSuccess(count, elapsed().inMillis)
            remaining -= count
          } catch {
            case e: Exception =>
              stats.sendRecordFailure(count, elapsed().inMillis)
              log.error(
                e,
                "Failed to send %s %d log entries to scribe server at %s:%d",
                category,
                count,
                hostname,
                port
              )
              closeSocket()
          }
        }
        updateLastTransmission()
      }
      stats.log()
    }
  }

  private def readResponseExpecting(socket: Socket, expectedReply: Array[Byte]): Unit = {
    var offset = 0

    val inStream = socket.getInputStream()

    // read response:
    val response = new Array[Byte](expectedReply.length)
    while (offset < response.length) {
      val n = inStream.read(response, offset, response.length - offset)
      if (n < 0) {
        throw new IOException("End of stream")
      }
      offset += n
    }
    if (!Arrays.equals(response, expectedReply)) {
      throw new IOException("Error response from scribe server: " + response.hexlify)
    }
  }

  // should be private, make it visible to tests
  private[logging] def makeBuffer(count: Int): ByteBuffer = {
    val texts = for (i <- 0 until count) yield queue.poll()
    val recordHeader = ByteBuffer.wrap(new Array[Byte](10 + category.length))
    recordHeader.order(ByteOrder.BIG_ENDIAN)
    recordHeader.put(11: Byte)
    recordHeader.putShort(1)
    recordHeader.putInt(category.length)
    recordHeader.put(category.getBytes(ISO_8859_1))
    recordHeader.put(11: Byte)
    recordHeader.putShort(2)

    val prefix = if (isArchaicServer()) OldScribePrefix else ScribePrefix
    val messageSize = (count * (recordHeader.capacity + 5)) + texts.foldLeft(0) { _ + _.length } + prefix.length + 5
    val buffer = ByteBuffer.wrap(new Array[Byte](messageSize + 4))
    buffer.order(ByteOrder.BIG_ENDIAN)
    // "framing":
    buffer.putInt(messageSize)
    buffer.put(prefix)
    buffer.putInt(count)
    for (text <- texts) {
      buffer.put(recordHeader.array)
      buffer.putInt(text.length)
      buffer.put(text)
      buffer.put(0: Byte)
    }
    buffer.put(0: Byte)
    buffer
  }

  private def closeSocket(): Unit = {
    synchronized {
      socket.foreach { s =>
        try {
          s.close()
        } catch {
          case _: Throwable =>
        }
      }
      socket = None
    }
  }

  override def close(): Unit = {
    stats.incrCloses()
    // TODO consider draining the flusher queue before returning
    // nothing stops a pending flush from opening a new socket
    closeSocket()
    flusher.shutdown()
  }

  override def publish(record: javalog.LogRecord): Unit = {
    if (record.getLoggerName == loggerName) return
    publish(getFormatter.format(record).getBytes("UTF-8"))
  }

  def publish(record: Array[Byte]): Unit = {
    stats.incrPublished()
    if (!queue.offer(record)) stats.incrDroppedRecords()
    if (Time.now.since(_lastTransmission) >= bufferTime) flush()
  }

  override def toString = {
    ("<%s level=%s hostname=%s port=%d scribe_buffer=%s " +
      "scribe_backoff=%s scribe_max_packet_size=%d formatter=%s>").format(
      getClass.getName,
      getLevel,
      hostname,
      port,
      bufferTime,
      connectBackoff,
      maxMessagesPerTransaction,
      formatter.toString
    )
  }

  private class ScribeHandlerStats(statsReceiver: StatsReceiver) {
    private var _lastLogStats = Time.epoch

    private val sentRecords = new AtomicLong()
    private val droppedRecords = new AtomicLong()
    private val connectionFailure = new AtomicLong()
    private val connectionSkipped = new AtomicLong()

    val totalSentRecords = statsReceiver.counter("sent_records")
    val totalDroppedRecords = statsReceiver.counter("dropped_records")
    val totalConnectionFailure = statsReceiver.counter("connection_failed")
    val totalConnectionSkipped = statsReceiver.counter("connection_skipped")
    val totalConnects = statsReceiver.counter("connects")
    val totalPublished = statsReceiver.counter("published")
    val totalCloses = statsReceiver.counter("closes")
    val instances = statsReceiver.addGauge("instances") { 1 }
    val unsentQueue = statsReceiver.addGauge("unsent_queue") { queueSize }

    val batchSizeBytesStat = statsReceiver.stat("batch_size_bytes")
    val batchSizeCountStat = statsReceiver.stat("batch_size_messages")

    val successLatencyStat = statsReceiver.stat("success_latency_ms")
    val failureLatencyStat = statsReceiver.stat("failure_latency_ms")

    /**
     * Update stats for the number of records sent and the histogram of latency to scribe.
     * @param count
     * The number of new messages sent to scribe.
     * @param latencyMs
     * The latency of the successful send to scribe.
     */
    def sendRecordSuccess(count: Int, latencyMs: Long): Unit = {
      sentRecords.addAndGet(count)
      totalSentRecords.incr(count)
      successLatencyStat.add(latencyMs)
    }

    /**
     * Update stats for the number of records dropped and the latency histogram for failed requests
     * to the scribe server.
     * @param count
     * The number of messages that failed to send to scribe and were dropped.
     * @param latencyMs
     * The latency of the failed request to scribe.
     */
    def sendRecordFailure(count: Int, latencyMs: Long): Unit = {
      incrDroppedRecords(count)
      failureLatencyStat.add(latencyMs)
    }

    /**
     * Update stats for the number of records dropped.
     * @param count
     * The number of messages that were dropped.
     */
    def incrDroppedRecords(count: Int = 1): Unit = {
      droppedRecords.addAndGet(count)
      totalDroppedRecords.incr(count)
    }

    def incrConnectionFailure(): Unit = {
      connectionFailure.incrementAndGet()
      totalConnectionFailure.incr()
    }

    def incrConnectionSkipped(): Unit = {
      connectionSkipped.incrementAndGet()
      totalConnectionSkipped.incr()
    }

    def incrPublished(): Unit = totalPublished.incr()

    def incrConnection(): Unit = totalConnects.incr()

    def incrCloses(): Unit = totalCloses.incr()

    def statBatchSize(numMessages: Int, bytes: Int): Unit = {
      batchSizeCountStat.add(numMessages)
      batchSizeBytesStat.add(bytes)
    }

    def log(): Unit = {
      synchronized {
        val period = Time.now.since(_lastLogStats)
        if (period > ScribeHandler.DefaultStatsReportPeriod) {
          val sent = sentRecords.getAndSet(0)
          val dropped = droppedRecords.getAndSet(0)
          val failed = connectionFailure.getAndSet(0)
          val skipped = connectionSkipped.getAndSet(0)
          ScribeHandler.log.debug(
            "sent records: %d, per second: %d, dropped records: %d, reconnection failures: %d, reconnection skipped: %d",
            sent,
            sent / period.inSeconds,
            dropped,
            failed,
            skipped
          )

          _lastLogStats = Time.now
        }
      }
    }
  }
}
