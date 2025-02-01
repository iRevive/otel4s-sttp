package sttp.client3.otel4s.trace

import sttp.model.Uri

trait UriTemplateClassifier {
  def classify(url: Uri): Option[String]
}

object UriTemplateClassifier {
  val none: UriTemplateClassifier = _ => None
}

