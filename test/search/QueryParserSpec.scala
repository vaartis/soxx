package soxx.search

import org.scalatest._

class QueryParserSpec extends FlatSpec with Matchers with LoneElement {
  behavior of "A query parser"

  def parse(query: String) = {
    import QueryParser.{ parseQuery, Success }

    val r = parseQuery(query)

    r shouldBe a [Success[_]]

    r.get
  }

  it should "parse a simple tag" in {
    parse("test_tag").loneElement shouldBe SimpleTag("test_tag")
  }

  it should "prase the NOT marker" in {
    val r = parse(""" !test_tag !"\"_\"" """)
    r should have length 2

    r(0) shouldBe TagNOT(SimpleTag("test_tag"))
    r(1) shouldBe TagNOT(ExactTag("\"_\""))
  }

  it should "parse an exact tag" in {
    parse("\"test_(exact_1)\"").loneElement shouldBe ExactTag("test_(exact_1)")
  }

  it should "parse a REGEX tag" in {
    val r = parse("""REGEX(test_\(\d+\\))""")
    r.head shouldBe a [RegexTag]

    val rt = r.head.asInstanceOf[RegexTag]

    rt.value.regex shouldBe """test_\(\d+\)"""
  }

  it should "parse all kinds of property tags" in {
    val r = parse("width >= 100 height < 250 md5 = test")

    r(0) shouldBe PropertyTag("width", ">=", "100")
    r(1) shouldBe PropertyTag("height", "<", "250")
    r(2) shouldBe PropertyTag("md5", "=", "test")
  }

  it should "parse multiple different tags separated with spaces" in {
    val r = parse("""!test_1 test_2 "tag_3_(test)" width >= 1280 REGEX(test_\(\d+\\))""")
    r should have length 5

    r(0) shouldBe TagNOT(SimpleTag("test_1"))
    r(1) shouldBe SimpleTag("test_2")
    r(2) shouldBe ExactTag("tag_3_(test)")
    r(3) shouldBe PropertyTag("width" , ">=", "1280")

    r(4) shouldBe a [RegexTag]
    r(4).asInstanceOf[RegexTag].value.regex shouldBe """test_\(\d+\)"""

  }

  it should "return an empty list when there are no tags" in {
    parse("") shouldBe empty
  }

  it should "recognize symbols in tags" in {
    val r = parse("some_body~ once_told_me_@ !\"the_world_is_(gonna)\"")
    r should have length 3

    r(0) shouldBe SimpleTag("some_body~")
    r(1) shouldBe SimpleTag("once_told_me_@")
    r(2) shouldBe TagNOT(ExactTag("the_world_is_(gonna)"))
  }

  it should "group all kinds of tags" in {
    val r = parse("""(i_aint !the_sharpest_tool~ "in_the_shed_(huh)" REGEX(test_\(\d+\\))) some_more_testing""")
    r should have length 2

    r(0) shouldBe a [TagGroup]
    val gr = r(0).asInstanceOf[TagGroup].value
    gr should have length 4

    gr(0) shouldBe SimpleTag("i_aint")
    gr(1) shouldBe TagNOT(SimpleTag("the_sharpest_tool~"))
    gr(2) shouldBe ExactTag("in_the_shed_(huh)")

    gr(3) shouldBe a [RegexTag]
    gr(3).asInstanceOf[RegexTag].value.regex shouldBe """test_\(\d+\)"""

    r(1) shouldBe SimpleTag("some_more_testing")
  }

  it should "parse AND as left associative" in {
    parse("a && b && c").loneElement shouldBe TagAND(TagAND(SimpleTag("a"), SimpleTag("b")), SimpleTag("c"))
  }

  it should "parse OR as left associative" in {
    parse("a || b || c").loneElement shouldBe TagOR(TagOR(SimpleTag("a"), SimpleTag("b")), SimpleTag("c"))
  }

  it should "parse complex logic operations" in {
    val r = parse("(a && !b) || (!c d)").head
    r shouldBe a [TagOR]

    val to = r.asInstanceOf[TagOR]

    to.left shouldBe a [TagGroup]
    val lh = to.left.asInstanceOf[TagGroup].value
    lh(0) shouldBe a [TagAND]
    val lha = lh(0).asInstanceOf[TagAND]

    lha.left shouldBe SimpleTag("a")
    lha.right shouldBe TagNOT(SimpleTag("b"))

    to.right shouldBe a [TagGroup]
    val rh = to.right.asInstanceOf[TagGroup].value

    rh(0) shouldBe TagNOT(SimpleTag("c"))
    rh(1) shouldBe SimpleTag("d")

  }
}
