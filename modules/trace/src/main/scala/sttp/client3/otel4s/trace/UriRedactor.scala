package sttp.client3.otel4s.trace

import sttp.model.Uri

trait UriRedactor {
  def redact(uri: Uri): Option[Uri]
}

object UriRedactor {

  val redactedUserInfo: UriRedactor =
    new UriRedactor {
      def redact(uri: Uri): Option[Uri] =
        Some(
          uri.copy(authority = uri.authority.map { authority =>
            authority.copy(userInfo =
              authority.userInfo.map(u => u.copy("REDACTED", u.password.map(_ => "REDACTED")))
            )
          })
        )
    }

}
