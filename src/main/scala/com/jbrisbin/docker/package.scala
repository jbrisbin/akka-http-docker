package com.jbrisbin

import java.time.Instant

/**
  * @author Jon Brisbin <jbrisbin@basho.com>
  */
package object docker {

  implicit def toEpoch(l: Long): Instant = Instant.ofEpochSecond(l)

}
