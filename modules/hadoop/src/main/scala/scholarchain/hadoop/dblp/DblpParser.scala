package scholarchain.hadoop.dblp

import java.io.{ByteArrayInputStream, IOException, StringReader}
import java.nio.charset.{Charset, StandardCharsets}
import javax.xml.XMLConstants
import javax.xml.parsers.{DocumentBuilderFactory, ParserConfigurationException}

import org.w3c.dom.{Element, Node}
import org.xml.sax.{EntityResolver, ErrorHandler, InputSource, SAXException, SAXParseException}

import scholarchain.hadoop.TextNormalization

/** One `author` element: its text (with any DBLP homonym suffix) and its `orcid` attribute. */
final case class DblpAuthor(name: String, orcid: Option[String])

/** The fields of one DBLP record that ScholarChain uses (schema note `dblp-raw/1.0.0`). */
final case class DblpRecord(
    recordType: String,
    key: Option[String],
    publtype: Option[String],
    mdate: Option[String],
    authors: Vector[DblpAuthor],
    editors: Vector[String],
    title: Option[String],
    year: Option[String],
    journal: Option[String],
    booktitle: Option[String],
    volume: Option[String],
    number: Option[String],
    pages: Option[String],
    ee: Vector[String],
    urls: Vector[String],
    crossref: Option[String]
)

/**
 * The named character entities the parser declares. They are the ISO 8859-1 (Latin-1) entity set of
 * HTML 4 (code points 160-255, in code point order), which is the set `dblp.dtd` uses for non-ASCII
 * characters (schema note: `ouml` is `&#246;`, `atilde` is `&#227;`). A reference to any other
 * entity makes the record fail to parse, and it is rejected (`malformed-xml`), never guessed.
 */
object DblpEntities {
  private val Latin1Names: Vector[String] = Vector(
    "nbsp",
    "iexcl",
    "cent",
    "pound",
    "curren",
    "yen",
    "brvbar",
    "sect",
    "uml",
    "copy",
    "ordf",
    "laquo",
    "not",
    "shy",
    "reg",
    "macr",
    "deg",
    "plusmn",
    "sup2",
    "sup3",
    "acute",
    "micro",
    "para",
    "middot",
    "cedil",
    "sup1",
    "ordm",
    "raquo",
    "frac14",
    "frac12",
    "frac34",
    "iquest",
    "Agrave",
    "Aacute",
    "Acirc",
    "Atilde",
    "Auml",
    "Aring",
    "AElig",
    "Ccedil",
    "Egrave",
    "Eacute",
    "Ecirc",
    "Euml",
    "Igrave",
    "Iacute",
    "Icirc",
    "Iuml",
    "ETH",
    "Ntilde",
    "Ograve",
    "Oacute",
    "Ocirc",
    "Otilde",
    "Ouml",
    "times",
    "Oslash",
    "Ugrave",
    "Uacute",
    "Ucirc",
    "Uuml",
    "Yacute",
    "THORN",
    "szlig",
    "agrave",
    "aacute",
    "acirc",
    "atilde",
    "auml",
    "aring",
    "aelig",
    "ccedil",
    "egrave",
    "eacute",
    "ecirc",
    "euml",
    "igrave",
    "iacute",
    "icirc",
    "iuml",
    "eth",
    "ntilde",
    "ograve",
    "oacute",
    "ocirc",
    "otilde",
    "ouml",
    "divide",
    "oslash",
    "ugrave",
    "uacute",
    "ucirc",
    "uuml",
    "yacute",
    "thorn",
    "yuml"
  )

  /** Entity name to code point. */
  val Table: Vector[(String, Int)] = Latin1Names.zipWithIndex.map { case (n, i) => n -> (160 + i) }

  require(Table.size == 96 && Table.last == ("yuml" -> 255), "Latin-1 entity table is incomplete")

  val Declarations: String =
    Table.map { case (n, cp) => s"""<!ENTITY $n "&#$cp;">""" }.mkString("\n")
}

/**
 * Parses one raw DBLP record. The record bytes are wrapped in a synthetic document whose internal
 * DTD subset declares [[DblpEntities]] only; external entities and DTDs are never loaded (XXE
 * safe), and a record cannot declare entities of its own. One parser per task (not thread-safe).
 *
 * @param encoding
 *   the dump's encoding; must be ASCII compatible (checked by [[scholarchain.hadoop.DblpSettings]])
 */
final class DblpParser(encoding: Charset) {

  private val factory: DocumentBuilderFactory = {
    val f = DocumentBuilderFactory.newInstance()
    f.setNamespaceAware(false)
    f.setValidating(false)
    f.setXIncludeAware(false)
    f.setExpandEntityReferences(true)
    f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    f.setFeature("http://xml.org/sax/features/external-general-entities", false)
    f.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
    f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    f
  }

  private val header: Array[Byte] =
    (s"""<?xml version="1.0" encoding="${encoding.name}"?>\n<!DOCTYPE dblp [\n""" +
      DblpEntities.Declarations + "\n]>\n<dblp>").getBytes(StandardCharsets.US_ASCII)
  private val footer: Array[Byte] = "</dblp>".getBytes(StandardCharsets.US_ASCII)

  private object Throwing extends ErrorHandler {
    def warning(e: SAXParseException): Unit = ()
    def error(e: SAXParseException): Unit = throw e
    def fatalError(e: SAXParseException): Unit = throw e
  }

  private object NoExternal extends EntityResolver {
    def resolveEntity(publicId: String, systemId: String): InputSource =
      new InputSource(new StringReader(""))
  }

  /** Decodes record bytes for reject excerpts. */
  def decode(bytes: Array[Byte]): String = new String(bytes, encoding)

  def parse(record: Array[Byte]): Either[String, DblpRecord] = {
    val bytes = new Array[Byte](header.length + record.length + footer.length)
    System.arraycopy(header, 0, bytes, 0, header.length)
    System.arraycopy(record, 0, bytes, header.length, record.length)
    System.arraycopy(footer, 0, bytes, header.length + record.length, footer.length)
    try {
      val builder = factory.newDocumentBuilder()
      builder.setErrorHandler(Throwing)
      builder.setEntityResolver(NoExternal)
      val root = builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement
      children(root) match {
        case Vector(e) => Right(extract(e))
        case other => Left(s"expected exactly one record element, found ${other.size}")
      }
    } catch {
      case e: SAXException => Left(s"XML parse error: ${e.getMessage}")
      case e: IOException => Left(s"XML read error: ${e.getMessage}")
      case e: ParserConfigurationException => throw new IllegalStateException(e)
    }
  }

  private def children(e: Element): Vector[Element] = {
    val nodes = e.getChildNodes
    (0 until nodes.getLength).toVector.map(nodes.item).collect {
      case c: Element if c.getNodeType == Node.ELEMENT_NODE => c
    }
  }

  private def text(e: Element): String = TextNormalization.collapse(e.getTextContent)

  private def attr(e: Element, name: String): Option[String] =
    Option(e.getAttribute(name)).map(_.trim).filter(_.nonEmpty)

  private def extract(rec: Element): DblpRecord = {
    val fields = children(rec)
    def all(tag: String): Vector[Element] = fields.filter(_.getTagName == tag)
    def first(tag: String): Option[String] = all(tag).headOption.map(text).filter(_.nonEmpty)
    DblpRecord(
      recordType = rec.getTagName,
      key = attr(rec, "key"),
      publtype = attr(rec, "publtype"),
      mdate = attr(rec, "mdate"),
      authors = all("author").map(a => DblpAuthor(text(a), attr(a, "orcid"))),
      editors = all("editor").map(text),
      title = first("title"),
      year = first("year"),
      journal = first("journal"),
      booktitle = first("booktitle"),
      volume = first("volume"),
      number = first("number"),
      pages = first("pages"),
      ee = all("ee").map(text).filter(_.nonEmpty),
      urls = all("url").map(text).filter(_.nonEmpty),
      crossref = first("crossref")
    )
  }
}
