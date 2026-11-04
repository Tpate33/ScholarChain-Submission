package scholarchain.hadoop.io

import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.compress.{CompressionCodecFactory, SplittableCompressionCodec}
import org.apache.hadoop.io.{BytesWritable, IOUtils, Text}
import org.apache.hadoop.mapreduce.lib.input.{FileInputFormat, FileSplit, LineRecordReader}
import org.apache.hadoop.mapreduce.{InputSplit, JobContext, RecordReader, TaskAttemptContext}

/** Locator of an input position: `<path>@<offset>`. Used as the key by every input format here. */
object Locator {
  def of(path: Path, offset: Long): String = s"$path@$offset"
}

/**
 * Line-oriented input (JSON Lines) like `TextInputFormat`, but the key is the locator
 * `<path>@<byte offset>` so that mappers can write located reject records (MultipleInputs hides the
 * file split from the mapper). Splits follow `TextInputFormat`: uncompressed and splittable codecs
 * are split; others are read whole.
 */
final class LocatedTextInputFormat extends FileInputFormat[Text, Text] {
  override def createRecordReader(
      split: InputSplit,
      ctx: TaskAttemptContext
  ): RecordReader[Text, Text] =
    new LocatedLineRecordReader

  override protected def isSplitable(context: JobContext, file: Path): Boolean = {
    val codec = new CompressionCodecFactory(context.getConfiguration).getCodec(file)
    codec == null || codec.isInstanceOf[SplittableCompressionCodec]
  }
}

final class LocatedLineRecordReader extends RecordReader[Text, Text] {
  private val lines = new LineRecordReader()
  private var path: Path = null
  private var key: Text = null

  override def initialize(split: InputSplit, ctx: TaskAttemptContext): Unit = {
    path = split.asInstanceOf[FileSplit].getPath
    lines.initialize(split, ctx)
  }

  override def nextKeyValue(): Boolean =
    if (lines.nextKeyValue()) {
      key = new Text(Locator.of(path, lines.getCurrentKey.get))
      true
    } else false

  override def getCurrentKey: Text = key
  override def getCurrentValue: Text = lines.getCurrentValue
  override def getProgress: Float = lines.getProgress
  override def close(): Unit = lines.close()
}

/**
 * One whole file per record (ORCID summaries: one XML file per ORCID record). Never split. Key: the
 * file path. Files larger than `scholarchain.orcid.maxRecordBytes` are returned empty with a
 * negative-length marker so that the mapper can reject them instead of exhausting memory.
 */
final class WholeFileInputFormat extends FileInputFormat[Text, BytesWritable] {
  override def createRecordReader(
      split: InputSplit,
      ctx: TaskAttemptContext
  ): RecordReader[Text, BytesWritable] = new WholeFileRecordReader

  override protected def isSplitable(context: JobContext, file: Path): Boolean = false
}

object WholeFileInputFormat {

  /** Value of a file that exceeded the size limit: a single 0xFF byte cannot start valid XML. */
  val OversizeMarker: Array[Byte] = Array(0xff.toByte)
}

final class WholeFileRecordReader extends RecordReader[Text, BytesWritable] {
  private var split: FileSplit = null
  private var ctx: TaskAttemptContext = null
  private var done = false
  private var key: Text = null
  private var value: BytesWritable = null

  override def initialize(s: InputSplit, c: TaskAttemptContext): Unit = {
    split = s.asInstanceOf[FileSplit]
    ctx = c
  }

  override def nextKeyValue(): Boolean =
    if (done) false
    else {
      done = true
      val conf = ctx.getConfiguration
      val max = scholarchain.hadoop.JobParams.orThrow(
        scholarchain.hadoop.JobParams
          .int(conf, scholarchain.hadoop.JobParams.OrcidMaxRecordBytes, 1)
      )
      val path = split.getPath
      val fs = path.getFileSystem(conf)
      val len = fs.getFileStatus(path).getLen
      key = new Text(path.toString)
      if (len > max) value = new BytesWritable(WholeFileInputFormat.OversizeMarker)
      else {
        val bytes = new Array[Byte](len.toInt)
        val in = fs.open(path)
        try IOUtils.readFully(in, bytes, 0, bytes.length)
        finally in.close()
        value = new BytesWritable(bytes)
      }
      true
    }

  override def getCurrentKey: Text = key
  override def getCurrentValue: BytesWritable = value
  override def getProgress: Float = if (done) 1.0f else 0.0f
  override def close(): Unit = ()
}
