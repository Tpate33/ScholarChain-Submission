package scholarchain.entityresolution.adjudication

/** A parsed JSON value that keeps object members in order, so duplicate keys stay visible. */
enum JValue {
  case JNull
  case JBool(value: Boolean)
  case JNum(text: String)
  case JStr(value: String)
  case JArr(items: Vector[JValue])
  case JObj(members: Vector[(String, JValue)])
}

/**
 * A small strict JSON (RFC 8259) parser for untrusted model output. Unlike a map-based parser it
 * refuses duplicate object keys (a later `"decision"` could otherwise silently override an earlier
 * one), bounds the input length and nesting depth, refuses trailing content, lone surrogates in
 * escapes, and unescaped control characters, and never evaluates anything.
 */
object StrictJson {
  val MaxLength: Int = 16384
  val MaxDepth: Int = 8

  final private class Fail(msg: String) extends RuntimeException(msg, null, false, false)

  def parse(text: String): Either[String, JValue] =
    if (text == null) Left("response is null")
    else if (text.length > MaxLength) Left(s"response longer than $MaxLength characters")
    else
      try {
        val p = new P(text)
        p.ws()
        val v = p.value(0)
        p.ws()
        if (p.i != text.length) Left(s"trailing content at offset ${p.i}") else Right(v)
      } catch { case f: Fail => Left(f.getMessage) }

  final private class P(s: String) {
    var i: Int = 0
    def fail(msg: String): Nothing = throw new Fail(s"$msg at offset $i")
    def ws(): Unit =
      while (i < s.length && (s(i) == ' ' || s(i) == '\t' || s(i) == '\n' || s(i) == '\r')) i += 1
    def expect(c: Char): Unit =
      if (i < s.length && s(i) == c) i += 1 else fail(s"expected '$c'")
    def literal(word: String, v: JValue): JValue =
      if (s.startsWith(word, i)) { i += word.length; v }
      else fail("invalid literal")

    def value(depth: Int): JValue = {
      if (depth > MaxDepth) fail(s"nesting deeper than $MaxDepth")
      if (i >= s.length) fail("unexpected end of input")
      s(i) match {
        case '{' => obj(depth)
        case '[' => arr(depth)
        case '"' => JValue.JStr(str())
        case 't' => literal("true", JValue.JBool(true))
        case 'f' => literal("false", JValue.JBool(false))
        case 'n' => literal("null", JValue.JNull)
        case c if c == '-' || (c >= '0' && c <= '9') => num()
        case _ => fail("unexpected character")
      }
    }

    def obj(depth: Int): JValue = {
      expect('{')
      ws()
      val members = Vector.newBuilder[(String, JValue)]
      val seen = scala.collection.mutable.HashSet.empty[String]
      if (i < s.length && s(i) == '}') { i += 1; JValue.JObj(Vector.empty) }
      else {
        var more = true
        while (more) {
          ws()
          if (i >= s.length || s(i) != '"') fail("expected a string key")
          val k = str()
          if (!seen.add(k)) fail(s"duplicate key '$k'")
          ws()
          expect(':')
          ws()
          members += (k -> value(depth + 1))
          ws()
          if (i < s.length && s(i) == ',') i += 1
          else { expect('}'); more = false }
        }
        JValue.JObj(members.result())
      }
    }

    def arr(depth: Int): JValue = {
      expect('[')
      ws()
      val items = Vector.newBuilder[JValue]
      if (i < s.length && s(i) == ']') { i += 1; JValue.JArr(Vector.empty) }
      else {
        var more = true
        while (more) {
          ws()
          items += value(depth + 1)
          ws()
          if (i < s.length && s(i) == ',') i += 1
          else { expect(']'); more = false }
        }
        JValue.JArr(items.result())
      }
    }

    private def hex4(): Int = {
      if (i + 4 > s.length) fail("truncated \\u escape")
      val h = s.substring(i, i + 4)
      if (!h.forall(c => Character.digit(c, 16) >= 0)) fail("invalid \\u escape")
      i += 4
      Integer.parseInt(h, 16)
    }

    def str(): String = {
      expect('"')
      val sb = new java.lang.StringBuilder
      var done = false
      while (!done) {
        if (i >= s.length) fail("unterminated string")
        val c = s(i)
        i += 1
        if (c == '"') done = true
        else if (c < 0x20) fail("unescaped control character in string")
        else if (Character.isSurrogate(c)) {
          // Raw surrogates must form a valid pair.
          if (Character.isHighSurrogate(c) && i < s.length && Character.isLowSurrogate(s(i))) {
            sb.append(c).append(s(i)); i += 1
          } else fail("lone surrogate in string")
        } else if (c != '\\') { val _ = sb.append(c) }
        else {
          if (i >= s.length) fail("truncated escape")
          val e = s(i)
          i += 1
          e match {
            case '"' => sb.append('"')
            case '\\' => sb.append('\\')
            case '/' => sb.append('/')
            case 'b' => sb.append('\b')
            case 'f' => sb.append('\f')
            case 'n' => sb.append('\n')
            case 'r' => sb.append('\r')
            case 't' => sb.append('\t')
            case 'u' =>
              val u = hex4()
              if (Character.isHighSurrogate(u.toChar)) {
                if (s.startsWith("\\u", i)) {
                  i += 2
                  val l = hex4()
                  if (!Character.isLowSurrogate(l.toChar)) fail("invalid surrogate pair escape")
                  sb.append(u.toChar).append(l.toChar)
                } else fail("lone surrogate escape")
              } else if (Character.isLowSurrogate(u.toChar)) fail("lone surrogate escape")
              else sb.append(u.toChar)
            case _ => fail("invalid escape")
          }
        }
      }
      sb.toString
    }

    def num(): JValue = {
      val start = i
      if (s(i) == '-') i += 1
      if (i < s.length && s(i) == '0') i += 1
      else if (i < s.length && s(i) >= '1' && s(i) <= '9')
        while (i < s.length && s(i).isDigit) i += 1
      else fail("invalid number")
      if (i < s.length && s(i) == '.') {
        i += 1
        if (!(i < s.length && s(i).isDigit)) fail("invalid fraction")
        while (i < s.length && s(i).isDigit) i += 1
      }
      if (i < s.length && (s(i) == 'e' || s(i) == 'E')) {
        i += 1
        if (i < s.length && (s(i) == '+' || s(i) == '-')) i += 1
        if (!(i < s.length && s(i).isDigit)) fail("invalid exponent")
        while (i < s.length && s(i).isDigit) i += 1
      }
      JValue.JNum(s.substring(start, i))
    }
  }
}
