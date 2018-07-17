package soxx.scrappers.traits

import scala.concurrent.Future

import xml.Elem
import play.api.libs.json.JsValue
import play.api.libs.ws.{ WSClient, WSResponse }

import soxx.scrappers.GenericScrapper

/** This and it's child traits allow easy image count retrieval from different formats.
  *
  * To use it, one needs to implement [[ImageCount.extractDataFromRequest]] (either in
  * a scrapper or, preferably, in a trait to allow reuse) that will extract data in the
  * expected format from the response made to `$baseUrl/$imageCountAddition`. Then, one
  * needs to override [[ImageCount.extractImageCount]] to extract the actual number
  * from the formatted data. Finally, one needs to call [[ImageCount.getImageCount]] to
  * get the actual image count.
 */
trait ImageCount[T] { this: GenericScrapper =>

  /** An addition to the base URL where one can get the total image count.
    *
    * It should not contain slashes at the beggining or at the end.
    */
  def imageCountAddition: String

  /** A function that does the actual request and returns the image count.
   */
  final def getImageCount: Future[Int] =
    ws.url(f"${baseUrl}/$imageCountAddition").get
      .map(extractDataFromRequest)
      .map(extractImageCount)

  /** A function to transform the request into the type processable by [[ImageCount.extractImageCount]].
    */
  protected def extractDataFromRequest(resp: WSResponse): T

  /** Extracts the image count number.
    *
    * One needs to override this function in the scrapper to suit
    * the data layout that comes in.
    */
  protected def extractImageCount(from: T): Int
}

trait JSONImageCount extends ImageCount[JsValue] { this: GenericScrapper =>
  override def extractDataFromRequest(resp: WSResponse) = resp.json
}

trait XMLImageCount extends ImageCount[Elem] { this: GenericScrapper =>
  override def extractDataFromRequest(resp: WSResponse) = resp.xml
}
