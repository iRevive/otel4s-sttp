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

import org.typelevel.otel4s.semconv.attributes._
import org.typelevel.otel4s.{Attribute, Attributes}
import sttp.client3.{Request, Response}
import sttp.model.HttpVersion

trait SpanAttributes { self =>

  def requestAttributes(request: Request[_, _]): Attributes

  def responseAttributes(response: Response[_]): Attributes

  final def and(that: SpanAttributes): SpanAttributes =
    new SpanAttributes {
      def requestAttributes(request: Request[_, _]): Attributes =
        self.requestAttributes(request) ++ that.requestAttributes(request)

      def responseAttributes(response: Response[_]): Attributes =
        self.responseAttributes(response) ++ that.responseAttributes(response)
    }
}

object SpanAttributes {

  val DefaultAllowedHeaders: Set[String] = Set(
    "Accept",
    "Accept-CH",
    "Accept-Charset",
    "Accept-CH-Lifetime",
    "Accept-Encoding",
    "Accept-Language",
    "Accept-Ranges",
    "Access-Control-Allow-Credentials",
    "Access-Control-Allow-Headers",
    "Access-Control-Allow-Origin",
    "Access-Control-Expose-Methods",
    "Access-Control-Max-Age",
    "Access-Control-Request-Headers",
    "Access-Control-Request-Method",
    "Age",
    "Allow",
    "Alt-Svc",
    "B3",
    "Cache-Control",
    "Clear-Site-Data",
    "Connection",
    "Content-Disposition",
    "Content-Encoding",
    "Content-Language",
    "Content-Length",
    "Content-Location",
    "Content-Range",
    "Content-Security-Policy",
    "Content-Security-Policy-Report-Only",
    "Content-Type",
    "Correlation-ID",
    "Cross-Origin-Embedder-Policy",
    "Cross-Origin-Opener-Policy",
    "Cross-Origin-Resource-Policy",
    "Date",
    "Deprecation",
    "Device-Memory",
    "DNT",
    "Early-Data",
    "ETag",
    "Expect",
    "Expect-CT",
    "Expires",
    "Feature-Policy",
    "Forwarded",
    "From",
    "Host",
    "If-Match",
    "If-Modified-Since",
    "If-None-Match",
    "If-Range",
    "If-Unmodified-Since",
    "Keep-Alive",
    "Large-Allocation",
    "Last-Modified",
    "Link",
    "Location",
    "Max-Forwards",
    "Origin",
    "Pragma",
    "Proxy-Authenticate",
    "Public-Key-Pins",
    "Public-Key-Pins-Report-Only",
    "Range",
    "Referer",
    "Referer-Policy",
    "Retry-After",
    "Save-Data",
    "Sec-CH-UA",
    "Sec-CH-UA-Arch",
    "Sec-CH-UA-Bitness",
    "Sec-CH-UA-Full-Version",
    "Sec-CH-UA-Full-Version-List",
    "Sec-CH-UA-Mobile",
    "Sec-CH-UA-Model",
    "Sec-CH-UA-Platform",
    "Sec-CH-UA-Platform-Version",
    "Sec-Fetch-Dest",
    "Sec-Fetch-Mode",
    "Sec-Fetch-Site",
    "Sec-Fetch-User",
    "Server",
    "Server-Timing",
    "SourceMap",
    "Strict-Transport-Security",
    "TE",
    "Timing-Allow-Origin",
    "Tk",
    "Trailer",
    "Transfer-Encoding",
    "Upgrade",
    "User-Agent",
    "Vary",
    "Via",
    "Viewport-Width",
    "Warning",
    "Width",
    "WWW-Authenticate",
    "X-B3-Sampled",
    "X-B3-SpanId",
    "X-B3-TraceId",
    "X-Content-Type-Options",
    "X-Correlation-ID",
    "X-DNS-Prefetch-Control",
    "X-Download-Options",
    "X-Forwarded-For",
    "X-Forwarded-Host",
    "X-Forwarded-Port",
    "X-Forwarded-Proto",
    "X-Forwarded-Scheme",
    "X-Frame-Options",
    "X-Permitted-Cross-Domain-Policies",
    "X-Powered-By",
    "X-Real-IP",
    "X-Request-ID",
    "X-Request-Start",
    "X-Runtime",
    "X-Scheme",
    "X-SourceMap",
    "X-XSS-Protection",
  )

  def openTelemetry(
      uriRedactor: UriRedactor,
      headersAllowedAsAttributes: Set[String]
  ): SpanAttributes =
    new SpanAttributes {

      def requestAttributes(request: Request[_, _]): Attributes = {
        val b = Attributes.newBuilder

        b += HttpAttributes.HttpRequestMethod(request.method.method)
        b ++= ServerAttributes.ServerAddress.maybe(request.uri.host)
        b ++= ServerAttributes.ServerPort.maybe(request.uri.port.map(_.toLong))
        b ++= UrlAttributes.UrlFull.maybe(uriRedactor.redact(request.uri).map(_.toString()))

        b ++= NetworkAttributes.NetworkProtocolVersion.maybe(request.httpVersion.map {
          case HttpVersion.HTTP_1   => "1.0"
          case HttpVersion.HTTP_1_1 => "1.1"
          case HttpVersion.HTTP_2   => "2"
          case HttpVersion.HTTP_3   => "3"
        })

        if (headersAllowedAsAttributes.nonEmpty) {
          b ++= request.headers
            .filter(header => headersAllowedAsAttributes.contains(header.name))
            .map(header =>
              Attribute(
                s"http.request.header.${header.name.toLowerCase}",
                Seq(header.value)
              )
            )
        }

        b ++= UrlAttributes.UrlScheme.maybe(request.uri.scheme)
        b ++= UserAgentAttributes.UserAgentOriginal.maybe(
          request.headers.find(_.name.equalsIgnoreCase("User-Agent")).map(_.value)
        )

        b.result()
      }

      def responseAttributes(response: Response[_]): Attributes = {
        val b = Attributes.newBuilder

        if (!response.code.isSuccess) {
          b += ErrorAttributes.ErrorType(response.code.toString)
        }
        b += HttpAttributes.HttpResponseStatusCode(response.code.code.toLong)

        if (headersAllowedAsAttributes.nonEmpty) {
          b ++= response.headers
            .filter(header => headersAllowedAsAttributes.contains(header.name))
            .map(header =>
              Attribute(
                s"http.request.header.${header.name.toLowerCase}",
                Seq(header.value)
              )
            )
        }

        b.result()
      }
    }

  val default: SpanAttributes =
    openTelemetry(UriRedactor.redactedUserInfo, DefaultAllowedHeaders)

}
