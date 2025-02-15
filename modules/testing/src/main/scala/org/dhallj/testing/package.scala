package org.dhallj.testing

import org.scalacheck.{Arbitrary, Gen}

package object instances extends ArbitraryInstances {
  def genNameString: Gen[String] = Gen.alphaStr
  def genTextString: Gen[String] = Gen.alphaStr
}