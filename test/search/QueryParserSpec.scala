import org.scalatest._

import scala.util.parsing.combinator._

import soxx.search._

class QueryParserSpec extends FlatSpec with Matchers with Inside {
  val parser = new QueryParser()

  behavior of "A query parser"

  it should "return a FullTag when one is given" in {
    import parser.Success

    inside(parser.parseQuery("test_tag")) { case Success(List(FullTag(matched)), _) =>
      matched shouldEqual "test_tag"
    }
  }

  it should "return an ExcludeTag when give a tag with a minus in the beginning" in {
    import parser.Success

    inside(parser.parseQuery("-test_tag")) { case Success(List(ExcludeTag(matched)), _) =>
      matched shouldEqual "test_tag"
    }
  }

  it should "return a RegexTag when given a regex~" in {
    import parser.Success

    inside(parser.parseQuery("regex~\\d~")) { case Success(List(RegexTag(matched)), _) =>
      matched.regex shouldEqual "\\d"
    }
  }

  it should "parse multiple tags separated with spaces" in {
    import parser.Success

    inside(parser.parseQuery("-test_1 test_2 regex~\\d~")) {
      case Success(List(ExcludeTag(excl), FullTag(fl), RegexTag(matched)), _) =>
        excl shouldEqual "test_1"
        fl shouldEqual "test_2"
        matched.regex shouldEqual "\\d"
    }
  }
}
