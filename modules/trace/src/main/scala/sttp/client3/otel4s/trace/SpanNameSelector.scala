package sttp.client3.otel4s.trace

import sttp.client3.Request

trait SpanNameSelector {
  def name(request: Request[_, _]): String
}

object SpanNameSelector {

  def default(classifier: UriTemplateClassifier): SpanNameSelector =
    new SpanNameSelector {
      def name(request: Request[_, _]): String = {
        val method = request.method.method
        classifier.classify(request.uri).fold(method)(classifier => s"$method $classifier")
      }
    }

}
