package com.jbrisbin

import java.nio.charset.Charset
import java.time.Instant

import akka.util.ByteString

/**
  * @author Jon Brisbin <jbrisbin@basho.com>
  */
package object docker {

  val charset = Charset.defaultCharset().toString

  implicit def toEpoch(l: Long): Instant = Instant.ofEpochSecond(l)

  implicit def byteString2String(bytes: ByteString): String = bytes.decodeString(charset)

}
