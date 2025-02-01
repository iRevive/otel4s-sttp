/*
 * Copyright 2025 Typelevel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
