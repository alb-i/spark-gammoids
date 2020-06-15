package plus.albrecht.matroids

import org.apache.spark.sql.{ColumnName, DataFrame}
import plus.albrecht.matroids.traits.SparkMatroid
import org.apache.spark.sql.functions.collect_set
import plus.albrecht.matroids.tests.axioms.BaseAxiomB2Spark
import plus.albrecht.matroids.tests.axioms.traits.AxiomTest
import plus.albrecht.tests.TestResult

import scala.reflect.ClassTag

/**
 *
 * This class implements the matroid interfaces for SparkMatroids,
 * unless the rank is zero; which would complicate a lot here and is of
 * limited use.
 *
 *
 * @param _ground_set the ground set of the matroid
 *
 * @param _m          the data stored in spark
 *
 * @param _rank       the rank of the matroid
 *
 * @tparam T matroid element type
 */
class SparkBasisMatroid[T](val _ground_set: Set[T],
                           val _m: traits.SparkMatroid[T],
                           val _rank: Int)
  extends traits.BasisMatroid[T]
    with traits.RankMatroid[T] {
  require(_rank > 0, "the rank must be positive.")

  /** constructor that obtains the rank from sparkMatroid
   *
   * @param groundSet
   *
   * @param sparkMatroid
   */
  def this(groundSet: Set[T], sparkMatroid: traits.SparkMatroid[T]) {
    this(groundSet, sparkMatroid,
      sparkMatroid.df(SparkMatroid.dataRank)
        .get.select(SparkMatroid.colRkRank)
        .head.getAs[Int](0))
  }

  /** constructor that obtains both ground set and rank from sparkMatroid
   *
   * @param sparkMatroid loop-less matroid or matroid that implements groundSet data frame
   */
  def this(sparkMatroid: traits.SparkMatroid[T]) {
    this(sparkMatroid.df(SparkMatroid.dataGroundSet)
      .get.select(SparkMatroid.colGsElement)
      .collect().map(r ⇒ r.getAs[T](0)).toSet, sparkMatroid)
  }

  /**
   * Store a copy of the data frame; throws if not available. It's not lazy so
   * it will throw on construction if _m does not provide the basis family interface.
   */
  val dfBasisFamily: DataFrame = _m.df(SparkMatroid.dataBasisFamily).get

  override def groundSet(): Iterable[T] = _ground_set

  override def rank(): Int = _rank

  override def basisFamily(): Iterable[Set[T]] = {
    dfBasisFamily
      .groupBy(SparkMatroid.colBfId)
      .agg(collect_set(SparkMatroid.colBfElement).as("output"))
      .select("output")
      .collect
      .map(r ⇒ r.getSeq(0).toSet.asInstanceOf[Set[T]])
      .toIterable
  }

  override def isBasis(set: Iterable[T]): Boolean = {
    val X = set.toSet
    if (X.size == _rank) {
      X.foldLeft(dfBasisFamily)({
        case (df, x0) ⇒
          df /* filter out rows that correspond to elements of bases that do not have x0 */
            .filter(new ColumnName(SparkMatroid.colBfElement).isin(x0))
            .select(SparkMatroid.colBfId)
            .join(df, SparkMatroid.colBfId)
      }).isEmpty == false
    } else false
  }

  override def rk(x: Iterable[T]): Int = {
    /* the rank of x is the maximal cardinality of the intersection of x
       with any of the bases of the matroid */


    x.toSet /* We have to convert x to a set because otherwise we would
                miscalculate the rank when an element occurs more than once!
    */ .foldLeft((0, dfBasisFamily))({
      case ((r0, fam0), e) ⇒
        val fam1 = fam0 /* filter all bases containing e */
          .filter(new ColumnName(SparkMatroid.colBfElement).isin(e))
          .select(SparkMatroid.colBfId)
          .join(fam0, SparkMatroid.colBfId)

        if (fam1.isEmpty) {
          /* e is in the closure of the previous elements */
          (r0, fam0)
        } else {
          /* e is not in the closure */
          (r0 + 1, fam1)
        }
    })._1
    /* So, how exactly does this work, one might ask?
       The above fold operation implicitly determines a maximal independent
       subset of x; which is the set consisting of those elements that
       reach 'e is not in the closure'. In the tuple (r0,fam0), r0 keeps track
       of the cardinality of the implicit maximal independent set with respect
       to all previous elements, and fam0 keeps track of all bases that contain
       this implicit set. So fam1 clearly consists of those bases of fam0
       that also contain e.

       Remember: all inclusion maximal independent subsets of X in a matroid
       have the same cardinality and thus the greedy approach works.
     */
  }

  /**
   * lazy baseAxiomB2Test; we always fail fast, because counterexamples come
   * in flocks if they exist
   */
  override lazy val baseAxiomB2Test: AxiomTest = new BaseAxiomB2Spark[T](this)


  /** lazy test: non-empty basis family? */

  override lazy val nonEmptyBasisFamilyTest: TestResult = {
    if (dfBasisFamily.limit(1).isEmpty)
      TestResult(false, List("[x] Basis family is empty violating the basis existence axiom."))
    else
      TestResult(true, List("[v] There is a basis."))
  }

  /** lazy test:  have all bases the right cardinality? */

  override lazy val basesCardinalityTest: TestResult = {
    val wrong = dfBasisFamily.groupBy(SparkMatroid.colBfId)
      .count.select("count")
      .filter(!new ColumnName("count").isin(rank())).count
    TestResult(wrong == 0, List(f"${if (wrong == 0) "[v]" else "[x]"} ${wrong} bases have " +
      f"the wrong cardinality."))
  }

  /** lazy test: are the bases all in the ground set? */

  override lazy val basesInMatroidTest: TestResult = {
    val wrong = dfBasisFamily
      .filter(!new ColumnName(SparkMatroid.colBfElement).isin(groundSet.toSeq: _*))
      .count
    TestResult(wrong == 0, List(f"${if (wrong == 0) "[v]" else "[x]"} ${wrong} base elements are " +
      f"non-matroid elements."))
  }
}

/** companion object */
object SparkBasisMatroid {
  /**
   * convenience constructor
   *
   * @param _ground_set
   *
   * @param _m spark matroid
   *
   * @param _rank
   *
   * @tparam T
   *
   * @return new SparkBasisMatroid
   */
  def apply[T](_ground_set: Set[T],
               _m: traits.SparkMatroid[T],
               _rank: Int): SparkBasisMatroid[T] = new SparkBasisMatroid[T](_ground_set, _m, _rank)

  /**
   * convenience constructor
   *
   * @param _ground_set
   *
   * @param _m spark matroid
   *
   * @tparam T
   *
   * @return new SparkBasisMatroid
   */
  def apply[T](_ground_set: Set[T],
               _m: traits.SparkMatroid[T]): SparkBasisMatroid[T] = new SparkBasisMatroid[T](_ground_set, _m)

  /**
   * convenience constructor
   *
   * @param _m spark matroid
   *
   * @tparam T
   *
   * @return new SparkBasisMatroid
   */
  def apply[T](_m: traits.SparkMatroid[T]): SparkBasisMatroid[T] = new SparkBasisMatroid[T](_m)

  /**
   * convenience constructor to move BasisMatroid to Spark
   *
   * @param basisMatroid
   *
   * @tparam T
   *
   * @return
   */
  def apply[T: ClassTag](basisMatroid: traits.BasisMatroid[T]): SparkBasisMatroid[T] = {
    val s = new BasisToSparkMatroid[T](basisMatroid)
    SparkBasisMatroid[T](basisMatroid.groundSetAsSet, s, basisMatroid.rank())
  }
}
