package scholarchain.storage.model

import java.nio.charset.StandardCharsets

import scholarchain.core.Canonical

/**
 * Immutable object content. The array is copied in and out, so no caller can change stored bytes
 * after the fact; equality is by content.
 */
final class Bytes private (private val data: Array[Byte]) {
  def size: Int = data.length
  def toArray: Array[Byte] = data.clone()

  /** SHA-256 of the content, 64 lowercase hex characters. */
  lazy val sha256: String = Canonical.sha256Hex(data)

  def utf8: String = new String(data, StandardCharsets.UTF_8)

  override def equals(other: Any): Boolean = other match {
    case b: Bytes => java.util.Arrays.equals(data, b.data)
    case _ => false
  }
  override def hashCode: Int = java.util.Arrays.hashCode(data)
  override def toString: String = s"Bytes(size=$size, sha256=$sha256)"
}

object Bytes {
  def of(a: Array[Byte]): Bytes = new Bytes(a.clone())
  def utf8(s: String): Bytes = new Bytes(s.getBytes(StandardCharsets.UTF_8))
}
