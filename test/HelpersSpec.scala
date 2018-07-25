package soxx.helpers

import org.scalatest._
import play.api.test.FakeRequest

import java.nio.file.Files
import scala.concurrent.duration._

class HelpersSpec extends FlatSpec with Matchers {

  it should "debounce a function to run only once per second" in {
    val fn = Helpers.debounce(1.second)((i: Int) => i + 1)

    fn(1) shouldBe Some(2)
    fn(1) shouldBe None

    Thread.sleep(1000)

    fn(1) shouldBe Some(2)
  }

  it should "read a file if it exists" in {
    val tempFile = Files.createTempFile(this.getClass.toString, "read_a_file")

    try {
      Files.write(tempFile, "test string".getBytes)

      Helpers.readFile(tempFile.toString) shouldBe Right("test string")
    } finally {
      Files.delete(tempFile)
    }
  }

  it should "return file reading errors" in {
    Helpers.readFile("does not exist").left.get.head shouldBe a [java.io.FileNotFoundException]
  }

  it should "construct a host with protocol from a request" in {
    import Helpers.RequestHelpers

    FakeRequest("GET", "http://example.com/something_unrelated").hostWithProtocol shouldBe "http://example.com"
  }
}
