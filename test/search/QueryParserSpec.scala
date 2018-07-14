package soxx.search

import org.scalatest._

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

  it should "return an empty list when there are no tags" in {
    import parser.Success

    inside(parser.parseQuery("")) { case Success(result, _) =>
      result.length shouldEqual 0
    }
  }

  it should "recognize symbols in tags" in {
    import parser.Success

    inside(parser.parseQuery("some_body~ once_told_me_! -the_world_is_(gonna) regex~roll-me...~")) {
      case Success(List(FullTag(fst), FullTag(snd), ExcludeTag(excl), RegexTag(smb)), _) =>
        fst shouldEqual "some_body~"
        snd shouldEqual "once_told_me_!"
        excl shouldEqual "the_world_is_(gonna)"
        smb.regex shouldEqual "roll-me..."
    }
  }
}
