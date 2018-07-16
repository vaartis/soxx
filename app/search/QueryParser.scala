package soxx.search

import scala.language.postfixOps
import scala.util.matching.Regex

import scala.util.parsing.combinator._

abstract class QueryTag
case class FullTag(value: String) extends QueryTag
case class ExcludeTag(value: String) extends QueryTag
case class RegexTag(value: Regex) extends QueryTag

/** Search query parser.
  *
  * Parses search queries separated by spaces, it recognizes full tags,
  * tag excludes and regex tags with a special syntax regex~regex goes here!~.
  * Note the the previous makes tildes otherwise unusable in regular expressions. I suppose this
  * issue will be addressed in the future.
  */
object QueryParser extends RegexParsers {
  override def skipWhitespace = true

  def fullTag: Parser[FullTag] = """[^\s\-]+""".r ^^ { x => FullTag(x.toString) }
  def excludeTag: Parser[QueryTag] = "-" ~> fullTag ^^ {
    case FullTag(v) => ExcludeTag(v)
  }

  def regexTag: Parser[QueryTag] = "regex~" ~> "[^~]+".r <~ "~"  ^^ { case x =>
    RegexTag(new Regex(x))
  }

  def theQueryParser = (regexTag | excludeTag | fullTag)*

  def parseQuery(theQuery: String) = parse(theQueryParser, theQuery)
}
