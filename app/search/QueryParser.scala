package soxx.search

import scala.language.postfixOps

import scala.util.matching.Regex

import scala.util.parsing.combinator._

sealed abstract class QueryTag

case class SimpleTag(value: String) extends QueryTag
case class ExactTag(value: String) extends QueryTag
case class RegexTag(value: Regex) extends QueryTag

case class TagAND(left: QueryTag, right: QueryTag) extends QueryTag
case class TagOR(left: QueryTag, right: QueryTag) extends QueryTag
case class TagNOT(value: QueryTag) extends QueryTag

case class TagGroup(value: List[QueryTag]) extends QueryTag

/** Search query parser.
  *
  * This parser is used to parse the search query into tags, which can include the following:
  * - a simple tag: anything that does not contain "(", ")", "!", "|", "&" or "\" can be considered a simple tag
  * - an exact tag: a quoted tag that can contain any symbols including an escaped quote symbol (so, for example, "\"_\"" means "_")
  * - logical NOT marker: if any kind of tag is preceeded with an exclamation mark, it's meaning is inverted (logical NOT)
  * - tag group: a list of tags that is surrounded by parenthesis to group them together
  * - logical operators: && and || can be used to construct complex logical operations, both of them are left associative
  * - regex tag: a tag that is marked with REGEX(the actual regex).
  *              It can contain a ) but it must be escaped as "\)", it will then be transformed into ).
  */
object QueryParser extends RegexParsers with PackratParsers {
  override def skipWhitespace = true

  // Basically, include anything except some special symbols
  def simpleTag: Parser[SimpleTag] = """[^\s\!\(\)\"\\\&\|]+""".r ^^ { SimpleTag(_) }

  // This will match anything quoted and also allow quoting with \"
  // In the final tag, \" will be replaced with "
  def exactTag: Parser[ExactTag] = "\"" ~> """((\\\")|[^\"])+""".r <~ "\"" ^^ { t => ExactTag(t.replace("\\\"", "\"")) }

  // Regular expression tag, ) can be escaped \\) (double backslash paren) because \) is a valid use case for tags.
  // So, for example, to search something with a closing paren one would need to write REGEX(\(.+\\\\\))
  def regexTag: Parser[RegexTag] =  "REGEX(" ~> """((\\\))|[^\)])+""".r <~ ")" ^^ { t =>
    RegexTag(new Regex(
      t.replace("""\)""", """)""")
    ))
  }

  def not: Parser[QueryTag] = "!" ~> aTag ^^ { TagNOT(_) }

  def groupedTags: PackratParser[TagGroup] = "(" ~> aTag.+ <~ ")" ^^ { TagGroup(_) }

  lazy val logic: PackratParser[QueryTag] = chainl1(
    withoutLogic,
    ( "&&" ^^^ { TagAND(_, _) } ) | ( "||" ^^^ { TagOR(_, _) } )
  )

  lazy val withoutLogic = groupedTags | regexTag | exactTag | not | simpleTag
  lazy val aTag = logic | withoutLogic

  lazy val severalTags: PackratParser[List[QueryTag]] = aTag*

  def parseQuery(theQuery: String) = parse(phrase(severalTags), theQuery)
}
