package org.jetbrains.plugins.textmate.regex.joni

import org.jcodings.specific.UTF8Encoding
import org.jetbrains.plugins.textmate.regex.RegexFacade
import org.jetbrains.plugins.textmate.regex.RegexFactory
import org.jetbrains.plugins.textmate.regex.TextMateString
import org.joni.Option
import org.joni.Regex
import org.joni.WarnCallback
import org.joni.exception.JOniException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets

class JoniRegexFactory : RegexFactory {
  companion object {
    private val FAILED_REGEX: Regex = Regex("^$", UTF8Encoding.INSTANCE)
    private val LOGGER: Logger = LoggerFactory.getLogger(JoniRegexFactory::class.java)
  }

  override fun regex(regexString: String): RegexFacade {
    val bytes = regexString.toByteArray(StandardCharsets.UTF_8)
    val regex = try {
      Regex(bytes, 0, bytes.size, Option.CAPTURE_GROUP, UTF8Encoding.INSTANCE, WarnCallback.NONE)
    }
    catch (e: JOniException) {
      LOGGER.info("Failed to parse textmate regex '{}' with {}: {}", regexString, e::class.java.getName(), e.message)
      FAILED_REGEX
    }
    return JoniRegexFacade(regex, regexString.contains("\\G"))
  }

  override fun string(string: CharSequence): TextMateString {
    return TextMateString.fromCharSequence(string)
  }
}