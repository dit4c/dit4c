package dit4c.scheduler.ssh

object RsaFactorizer {
  // From http://tools.ietf.org/html/rfc2313#section-7.2
  type Modulus         = BigInt // modulus n
  type PublicExponent  = BigInt // public exponent e
  type PrivateExponent = BigInt // private exponent d
  type Prime1          = BigInt // prime factor p of n
  type Prime2          = BigInt // prime factor q of n
  type Exponent1       = BigInt // d mod (p-1)
  type Exponent2       = BigInt // d mod (q-1)
  // Chinese Remainder Theorem coefficient q-1 mod p
  type Coefficient     = BigInt

  type Pkcs1Params = (
      Modulus,
      PublicExponent,
      PrivateExponent,
      Prime1,
      Prime2,
      Exponent1,
      Exponent2,
      Coefficient)

  /**
   * Based on "Twenty Years of Attacks on the RSA Cryptosystem" by Dan Boneh
   * (specifically Fact 1):
   * https://crypto.stanford.edu/~dabo/papers/RSA-survey.pdf
   * and "Handbook of Applied Cryptography" Section 8.2.2(i):
   * http://cacr.uwaterloo.ca/hac/about/chap8.pdf
   * as explained by Robert Mason's excellent answer here:
   * http://crypto.stackexchange.com/a/14713/34512
   */
  def apply(
      n: Modulus,
      e: PublicExponent,
      d: PrivateExponent): Pkcs1Params = {
    // For k=e*d−1, we know:
    // * k is even
    // * k=(2^t)*r provided r is odd and t ≥ 1
    val k = e * d - 1
    val (r, t) = factorizeK(k)
    // Pick a "random" x such that 2 ≤ x ≤ n
    val xs = Stream.from(2).map(BigInt(_)).takeWhile(_ < n)
    // Determine first s(i) such that:
    // * abs(s(i)) is not 1
    // * abs(s(i-1)) is 1
    // If none fits, pick a new x
    val si = xs.flatMap(x => siCandidate(x, k, r, t, n)).head
    val p = (si - 1).gcd(n)
    val q = n / p
    (n, e, d, p, q, exponent(d,p), exponent(d,q), coefficient(p,q))
  }

  /**
   * Produce (r, t) such that:
   * - k=(2^t)*r
   * - r is odd
   * - t ≥ 1, but preferably small
   */
  def factorizeK(k: BigInt): (BigInt, Int) = {
    assert(k % 2 == BigInt(0) && k != BigInt(0))
    Stream.from(1) // start from r = k / 2 and t = 1
      .filter { t => k.testBit(t) } // bit is 1, so k >> t would be odd
      .map { t => (k >> t, t) }
      .head
  }

  /**
   * Determine first s(i) such that:
   * - abs(s(i)) is not 1
   * - abs(s(i-1)) is 1
   */
  def siCandidate(x: BigInt, k: BigInt, r: BigInt, t: Int, n: BigInt): Option[BigInt] =
    sSeries(x, k, r, t, n)
      .sliding(2, 1)
      .collectFirst {
        case Seq(oldSi: BigInt, si: BigInt) if oldSi == BigInt(1) && !(si == BigInt(1) || n - BigInt(1) == si) =>
          si
      }

  def sSeries(x: BigInt, k: BigInt, r: BigInt, t: Int, n: BigInt): Seq[BigInt] =
    sSeries(x, k, r, t, n, 1).map(_ % n)

  def sSeries(x: BigInt, k: BigInt, r: BigInt, t: Int, n: BigInt, i: Int): List[BigInt] =
    // s(t) = x^r
    if (t == i) x.modPow(r, n) :: Nil
    // s(i) = s(i+1)^2
    else {
      val rest = sSeries(x, k, r, t, n, i + 1)
      rest.head.modPow(2,n) :: rest
    }

  def exponent(d: PrivateExponent, prime: BigInt) = d % (prime - 1)

  // Chinese Remainder Theorem coefficient q-1 mod p
  def coefficient(p: Prime1, q: Prime2): Coefficient = (q - 1) % p

}