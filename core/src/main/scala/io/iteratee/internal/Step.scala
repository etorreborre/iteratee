package io.iteratee.internal

import algebra.Monoid
import cats.{ Applicative, Functor, Monad, MonoidK }
import io.iteratee.Iteratee

/**
 * Represents the current state of an [[Iteratee]].
 *
 * An [[Iteratee]] has either already calculated a result ([[Step.done]]) or is
 * waiting for more data ([[Step.cont]]).
 *
 * @tparam F The effect type constructor
 * @tparam E The type of the input data
 * @tparam A The type of the result calculated by the [[Iteratee]]
 */
sealed abstract class Step[F[_], E, A] extends Serializable {
  /**
   * The [[Iteratee]]'s result.
   *
   * In some cases we know that an iteratee has been constructed in such a way
   * that it must be in a completed state, even though that's not tracked by the
   * type system. This method provides (unsafe) access to the result for use in
   * these situations.
   */
  private[iteratee] def unsafeValue: A

  /**
   * Reduce this [[Step]] to a value using the given pair of functions.
   */
  final def fold[Z](cont: (Input[E] => F[Step[F, E, A]]) => Z, done: A => Z, early: (A, Input[E]) => Z): Z = foldWith(
    new Step.Folder[F, E, A, Z] {
      final def onCont(k: Input[E] => F[Step[F, E, A]]): Z = cont(k)
      final def onDone(value: A): Z = done(value)
      override final def onEarly(value: A, remainder: Input[E]): Z = early(value, remainder)
    }
  )

  /**
   * Reduce this [[Step]] to a value using the given pair of functions.
   *
   * This method is provided primarily for internal use and for cases where the
   * expense of allocating multiple function objects and collection instances is
   * known to be too high. In most cases [[fold]] should be preferred.
   */
  def foldWith[Z](folder: Step.Folder[F, E, A, Z]): Z

  def isDone: Boolean

  /**
   * Map a function over the value of this [[Step]].
   */
  def map[B](f: A => B)(implicit F: Functor[F]): Step[F, E, B]

  /**
   * Map a function returning a [[Step]] in a monadic context over the value of
   * this [[Step]] and flatten the result.
   */
  def bindF[B](f: A => F[Step[F, E, B]])(implicit F: Monad[F]): F[Step[F, E, B]]

  /**
   * Apply this [[Step]] to an [[Input]].
   */
  def feed(in: Input[E])(implicit F: Applicative[F]): F[Step[F, E, A]]
}

private[iteratee] abstract class DoneStep[F[_], E, A](final val unsafeValue: A) extends Step[F, E, A] {
  final def isDone: Boolean = true
  final def feed(in: Input[E])(implicit F: Applicative[F]): F[Step[F, E, A]] = F.pure(this)
}

/**
 * @groupname Constructors Constructors
 * @groupprio Constructors 0
 *
 * @groupname Utilities Miscellaneous utilities
 * @groupprio Utilities 1
 *
 * @groupname Collection Collection operation steps
 * @groupprio Collection 2
 */
final object Step {
  /**
   * Represents a pair of functions that can be used to reduce a [[Step]] to a
   * value.
   *
   * Combining two "functions" into a single class allows us to avoid
   * allocations.
   *
   * @group Utilities
   *
   * @tparam E The type of the input data
   * @tparam F The effect type constructor
   * @tparam A The type of the result calculated by the [[Iteratee]]
   * @tparam B The type of the result of the fold
   */
  abstract class Folder[F[_], E, A, Z] extends Function[Step[F, E, A], Z] {
    def onCont(k: Input[E] => F[Step[F, E, A]]): Z
    def onDone(value: A): Z
    def onEarly(value: A, remainder: Input[E]): Z = onDone(value)
    final def apply(step: Step[F, E, A]): Z = step.foldWith(this)
  }

  /**
   * Create an incomplete state that will use the given function to process the next input.
   *
   * @group Constructors
   */
  final def cont[F[_], E, A](k: Input[E] => F[Step[F, E, A]]): Step[F, E, A] = new Step[F, E, A] {
    private[iteratee] final def unsafeValue: A = diverge[A]
    final def isDone: Boolean = false
    final def foldWith[B](folder: Folder[F, E, A, B]): B = folder.onCont(k)
    final def map[B](f: A => B)(implicit F: Functor[F]): Step[F, E, B] = cont(in =>
      F.map(k(in))(_.map(f))
    )
    final def bindF[B](f: A => F[Step[F, E, B]])(implicit F: Monad[F]): F[Step[F, E, B]] = F.pure(
      cont(u => F.flatMap(k(u))(_.bindF(f)))
    )
    final def feed(in: Input[E])(implicit F: Applicative[F]): F[Step[F, E, A]] = k(in)
  }

  /**
   * Create an incomplete state that will use the given pure function to process the next input.
   *
   * @group Constructors
   */
  final def pureCont[F[_], E, A](k: Input[E] => Step[F, E, A])(implicit
    F0: Applicative[F]
  ): Step[F, E, A] = new Step[F, E, A] {
    private[iteratee] final def unsafeValue: A = diverge[A]
    final def isDone: Boolean = false
    final def foldWith[B](folder: Folder[F, E, A, B]): B = folder.onCont(in => F0.pure(k(in)))
    final def map[B](f: A => B)(implicit F: Functor[F]): Step[F, E, B] = pureCont(in =>
      k(in).map(f)
    )
    final def bindF[B](f: A => F[Step[F, E, B]])(implicit F: Monad[F]): F[Step[F, E, B]] = F.pure(
      cont(in => k(in).bindF(f))
    )
    final def feed(in: Input[E])(implicit F: Applicative[F]): F[Step[F, E, A]] = F.pure(k(in))
  }

  /**
   * Create a new completed state with the given result and leftover input.
   *
   * @group Constructors
   */
  final def done[F[_], E, A](value: A): Step[F, E, A] = new DoneStep[F, E, A](value) {
    final def foldWith[B](folder: Folder[F, E, A, B]): B = folder.onDone(value)
    final def map[B](f: A => B)(implicit F: Functor[F]): Step[F, E, B] = done(f(value))
    final def bindF[B](f: A => F[Step[F, E, B]])(implicit F: Monad[F]): F[Step[F, E, B]] = f(value)
  }

  /**
   * Create a new completed state with the given result and leftover input.
   *
   * @group Constructors
   */
  final def early[F[_], E, A](value: A, remaining: Input[E]): Step[F, E, A] = new DoneStep[F, E, A](value) {
    final def foldWith[B](folder: Folder[F, E, A, B]): B = folder.onEarly(value, remaining)
    final def map[B](f: A => B)(implicit F: Functor[F]): Step[F, E, B] = early(f(value), remaining)

    final def bindF[B](f: A => F[Step[F, E, B]])(implicit F: Monad[F]): F[Step[F, E, B]] =
      F.flatMap(f(value))(
        _.foldWith(
          new Folder[F, E, B, F[Step[F, E, B]]] {
            final def onCont(k: Input[E] => F[Step[F, E, B]]): F[Step[F, E, B]] = k(remaining)
            final def onDone(aa: B): F[Step[F, E, B]] = F.pure(early(aa, remaining))
          }
        )
      )
  }

  /**
   * Create a new completed state with the given result and leftover input.
   *
   * @group Constructors
   */
  final def ended[F[_], E, A](value: A): Step[F, E, A] = new DoneStep[F, E, A](value) {
    final def foldWith[B](folder: Folder[F, E, A, B]): B = folder.onEarly(value, Input.end)
    final def map[B](f: A => B)(implicit F: Functor[F]): Step[F, E, B] = ended(f(value))

    final def bindF[B](f: A => F[Step[F, E, B]])(implicit F: Monad[F]): F[Step[F, E, B]] =
      F.flatMap(f(value))(
        _.foldWith(
          new Folder[F, E, B, F[Step[F, E, B]]] {
            final def onCont(k: Input[E] => F[Step[F, E, B]]): F[Step[F, E, B]] = k(Input.end)
            final def onDone(aa: B): F[Step[F, E, B]] = F.pure(ended(aa))
          }
        )
      )
  }

  /**
   * Lift a monadic value into a [[Step]].
   *
   * @group Utilities
   */
  final def liftM[F[_], E, A](fa: F[A])(implicit F: Monad[F]): F[Step[F, E, A]] = F.map(fa)(a => done(a))

  /**
   * Collapse a nested [[Step]] into one layer.
   *
   * @group Utilities
   */
  final def joinI[F[_], A, B, C](step: Step[F, A, Step[F, B, C]])(implicit F: Monad[F]): F[Step[F, A, C]] = {
    def check: Step[F, B, C] => F[Step[F, A, C]] = _.foldWith(
      new Folder[F, B, C, F[Step[F, A, C]]] {
        final def onCont(k: Input[B] => F[Step[F, B, C]]): F[Step[F, A, C]] = F.flatMap(k(Input.end))(
          s => if (s.isDone) check(s) else diverge
        )
        final def onDone(value: C): F[Step[F, A, C]] =
          F.pure(Step.done(value))
      }
    )

    step.bindF(check)
  }

  class FoldFolder[F[_]: Applicative, E, A](acc: A, f: (A, E) => A) extends Input.Folder[E, Step[F, E, A]] {
    final def onEl(e: E): Step[F, E, A] = fold(f(acc, e))(f)
    final def onChunk(e1: E, e2: E, es: Vector[E]): Step[F, E, A] = fold(es.foldLeft(f(f(acc, e1), e2))(f))(f)
    final def onEnd: Step[F, E, A] = Step.early(acc, Input.end)
  }

  /**
   * A [[Step]] that folds a stream using an initial value and an accumulation
   * function.
   *
   * @group Collection
   */
  final def fold[F[_]: Applicative, E, A](init: A)(f: (A, E) => A): Step[F, E, A] =
    pureCont(new FoldFolder[F, E, A](init, f))

  /**
   * A [[Step]] that folds a stream using an initial value and a monadic
   * accumulation function.
   *
   * @group Collection
   */
  final def foldM[F[_], E, A](init: A)(f: (A, E) => F[A])(implicit F: Monad[F]): Step[F, E, A] = {
    def step(acc: A)(in: Input[E]): F[Step[F, E, A]] = in.foldWith(
      new Input.Folder[E, F[Step[F, E, A]]] {
        final def onEl(e: E): F[Step[F, E, A]] = F.map(f(acc, e))(a => Step.cont(step(a)))
        final def onChunk(e1: E, e2: E, es: Vector[E]): F[Step[F, E, A]] = F.map(
          es.foldLeft(F.flatMap(f(acc, e1))(a => f(a, e2)))((fa, e) => F.flatMap(fa)(a => f(a, e)))
        )(a => Step.cont(step(a)))
        final def onEnd: F[Step[F, E, A]] = F.pure(Step.early(acc, Input.end))
      }
    )

    Step.cont(step(init))
  }

  /**
   * A [[Step]] that collects all the elements in a stream in a vector.
   *
   * @group Collection
   */
  final def drain[F[_], A](implicit F: Applicative[F]): Step[F, A, Vector[A]] = {
    def loop(acc: Vector[A])(in: Input[A]): Step[F, A, Vector[A]] = in.foldWith(
      new Input.Folder[A, Step[F, A, Vector[A]]] {
        final def onEl(e: A): Step[F, A, Vector[A]] = Step.pureCont(a => loop(acc :+ e)(a))
        final def onChunk(e1: A, e2: A, es: Vector[A]): Step[F, A, Vector[A]] =
          Step.pureCont(a => loop((acc :+ e1 :+ e2) ++ es)(a))
        final def onEnd: Step[F, A, Vector[A]] = Step.early(acc, in)
      }
    )

    Step.pureCont(a => loop(Vector.empty)(a))
  }

  /**
   * A [[Step]] that collects all the elements in a stream in a given collection
   * type.
   *
   * @group Collection
   */
  final def drainTo[F[_], A, C[_]](implicit
    F: Monad[F],
    M: MonoidK[C],
    C: Applicative[C]
  ): Step[F, A, C[A]] = {
    def loop(acc: C[A])(in: Input[A]): Step[F, A, C[A]] = in.foldWith(
      new Input.Folder[A, Step[F, A, C[A]]] {
        final def onEl(e: A): Step[F, A, C[A]] = Step.pureCont(loop(M.combine(acc, C.pure(e))))
        final def onChunk(e1: A, e2: A, es: Vector[A]): Step[F, A, C[A]] = Step.pureCont(
          loop(
            es.foldLeft(
              M.combine(M.combine(acc, C.pure(e1)), C.pure(e2))
            )((a, e) => M.combine(a, C.pure(e)))
          )
        )
        final def onEnd: Step[F, A, C[A]] = Step.early(acc, in)
      }
    )

    Step.pureCont(loop(M.empty))
  }

  /**
   * A [[Step]] that returns the first value in a stream.
   *
   * @group Collection
   */
  final def head[F[_]: Applicative, E]: Step[F, E, Option[E]] = {
    def loop(in: Input[E]): Step[F, E, Option[E]] = in.foldWith(
      new Input.Folder[E, Step[F, E, Option[E]]] {
        final def onEl(e: E): Step[F, E, Option[E]] = Step.done(Some(e))
        final def onChunk(e1: E, e2: E, es: Vector[E]): Step[F, E, Option[E]] =
          Step.early(
            Some(e1),
            if (es.isEmpty) Input.el(e2) else Input.chunk(e2, es(0), es.drop(1))
          )
        final def onEnd: Step[F, E, Option[E]] = Step.early(None, in)
      }
    )

    Step.pureCont(loop)
  }

  /**
   * A [[Step]] that returns the first value in a stream without consuming it.
   *
   * @group Collection
   */
  final def peek[F[_]: Applicative, E]: Step[F, E, Option[E]] = {
    def loop(in: Input[E]): Step[F, E, Option[E]] = in.foldWith(
      new Input.Folder[E, Step[F, E, Option[E]]] {
        final def onEl(e: E): Step[F, E, Option[E]] = Step.early(Some(e), in)
        final def onChunk(e1: E, e2: E, es: Vector[E]): Step[F, E, Option[E]] = Step.early(Some(e1), in)
        final def onEnd: Step[F, E, Option[E]] = Step.early(None, in)
      }
    )

    Step.pureCont(loop)
  }

  /**
   * A [[Step]] that returns a given number of the first values in a stream.
   *
   * @group Collection
   */
  final def take[F[_]: Applicative, A](n: Int): Step[F, A, Vector[A]] = {
    def loop(acc: Vector[A], n: Int)(in: Input[A]): Step[F, A, Vector[A]] = in.foldWith(
      new Input.Folder[A, Step[F, A, Vector[A]]] {
        final def onEl(e: A): Step[F, A, Vector[A]] =
          if (n == 1) Step.done(acc :+ e) else Step.pureCont(loop(acc :+ e, n - 1))
        final def onChunk(e1: A, e2: A, es: Vector[A]): Step[F, A, Vector[A]] = {
          val diff = n - (es.size + 2)

          if (diff > 0) Step.pureCont(loop(acc ++ in.toVector, diff)) else {
            if (diff == 0) Step.done(acc ++ in.toVector) else {
              val (taken, left) = in.toVector.splitAt(n)

              Step.early(
                acc ++ taken,
                if (left.size == 1) Input.el(left(0)) else Input.chunk(left(0), left(1), left.drop(2))
              )
            }
          }
        }
        final def onEnd: Step[F, A, Vector[A]] = Step.early(acc, in)
      }
    )

    if (n <= 0) {
      Step.done[F, A, Vector[A]](Vector.empty)
    } else {
      Step.pureCont(loop(Vector.empty, n))
    }
  }

  /**
   * A [[Step]] that returns values from a stream as long as they satisfy the
   * given predicate.
   *
   * @group Collection
   */
  final def takeWhile[F[_]: Applicative, A](p: A => Boolean): Step[F, A, Vector[A]] = {
    def loop(acc: Vector[A])(in: Input[A]): Step[F, A, Vector[A]] = in.foldWith(
      new Input.Folder[A, Step[F, A, Vector[A]]] {
        final def onEl(e: A): Step[F, A, Vector[A]] =
          if (p(e)) Step.pureCont(loop(acc :+ e)) else Step.early(acc, in)

        final def onChunk(e1: A, e2: A, es: Vector[A]): Step[F, A, Vector[A]] = {
          val (before, after) = in.toVector.span(p)

          if (after.isEmpty) {
            Step.pureCont(loop(acc ++ before))
          } else if (after.size == 1) {
            Step.early(acc ++ before, Input.el(after(0)))
          } else {
            Step.early(acc ++ before, Input.chunk(after(0), after(1), after.drop(2)))
          }
        }
        final def onEnd: Step[F, A, Vector[A]] = Step.early(acc, in)
      }
    )

    Step.pureCont(loop(Vector.empty))
  }

  /**
   * A [[Step]] that drops a given number of the values from a stream.
   *
   * @group Collection
   */
  final def drop[F[_]: Applicative, E](n: Int): Step[F, E, Unit] = {
    def loop(in: Input[E]): Step[F, E, Unit] = in.foldWith(
      new Input.Folder[E, Step[F, E, Unit]] {
        final def onEl(e: E): Step[F, E, Unit] = drop(n - 1)
        final def onChunk(e1: E, e2: E, es: Vector[E]): Step[F, E, Unit] = {
          val len = es.size + 2

          if (len <= n) drop(n - len) else {
            val dropped = in.toVector.drop(n)

            Step.early(
              (),
              if (dropped.size == 1) Input.el(dropped(0)) else Input.chunk(dropped(0), dropped(1), dropped.drop(2))
            )
          }
        }
        final def onEnd: Step[F, E, Unit] = Step.early((), in)
      }
    )

    if (n <= 0) Step.done(()) else Step.pureCont(loop)
  }

  /**
   * A [[Step]] that drops values from a stream as long as they satisfy the
   * given predicate.
   *
   * @group Collection
   */
  final def dropWhile[F[_], E](p: E => Boolean)(implicit F: Applicative[F]): Step[F, E, Unit] = {
    val suif = new StepUnitInputFolder[F, E] {
      final def onEl(e: E): Step[F, E, Unit] = if (p(e)) dropWhile(p) else Step.early((), Input.el(e))
      final def onChunk(e1: E, e2: E, es: Vector[E]): Step[F, E, Unit] = {
        val after = (e1 +: e2 +: es).dropWhile(p)

        if (after.isEmpty) dropWhile(p) else if (after.size == 1) Step.early((), Input.el(after(0))) else
          Step.early((), Input.chunk(after(0), after(1), after.drop(2)))
      }
    }

    Step.pureCont(suif.next)
  }

  /**
   * Zip two steps into a single step that returns a pair.
   *
   * @group Collection
   */
  final def zip[F[_], E, A, B](stepA: Step[F, E, A], stepB: Step[F, E, B])
    (implicit F: Monad[F]): F[Step[F, E, (A, B)]] = {
    type Pair[Z] = (Option[(Z, Option[Input[E]])], Step[F, E, Z])

    def paired[Z](s: Step[F, E, Z]): Step[F, E, Pair[Z]] = Step.done(
      s.foldWith(
        new Step.Folder[F, E, Z, Pair[Z]] {
          def onCont(k: Input[E] => F[Step[F, E, Z]]): Pair[Z] = (None, Step.cont(k))
          def onDone(value: Z): Pair[Z] = (Some((value, None)), Step.done(value))
          def onDone(value: Z, remainder: Input[E]): Pair[Z] =
            (Some((value, Some(remainder))), Step.early(value, remainder))
        }
      )
    )

    def shorter(a: Option[Input[E]], b: Option[Input[E]]): Option[Input[E]] =
      (a, b) match {
        case (Some(ia), _) if ia.isEnd => a
        case (_, Some(ib)) if ib.isEnd => b
        case (None, _) => None
        case (_, None) => None
        case (Some(as), Some(bs)) => if (as.toVector.size <= bs.toVector.size) a else b
      }

    def loop(stepA: Step[F, E, A], stepB: Step[F, E, B])(in: Input[E]): F[Step[F, E, (A, B)]] =
      in.foldWith(
        new Input.Folder[E, F[Step[F, E, (A, B)]]] {
          def onEl(e: E): F[Step[F, E, (A, B)]] = F.flatMap(stepA.feed(in))(fsA =>
            paired(fsA).bindF {
              case (pairA, nextA) =>
                F.flatMap(stepB.feed(in))(fsB =>
                  paired(fsB).bindF {
                    case (pairB, nextB) => F.pure(
                      (pairA, pairB) match {
                        case (Some((resA, remA)), Some((resB, remB))) =>
                          shorter(remA, remB) match {
                            case None => Step.done[F, E, (A, B)]((resA, resB))
                            case Some(rem) => Step.early[F, E, (A, B)]((resA, resB), rem)
                          }
                        case (Some((resA, _)), None) => nextB.map((resA, _))
                        case (None, Some((resB, _))) => nextA.map((_, resB))
                        case _ => Step.cont(loop(nextA, nextB))
                      }
                    )
                  }
                )
            }
          )

          def onChunk(e1: E, e2: E, es: Vector[E]): F[Step[F, E, (A, B)]] = F.flatMap(stepA.feed(in))(fsA =>
            paired(fsA).bindF {
              case (pairA, nextA) =>
                F.flatMap(stepB.feed(in))(fsB =>
                  paired(fsB).bindF {
                    case (pairB, nextB) => F.pure(
                      (pairA, pairB) match {
                        case (Some((resA, remA)), Some((resB, remB))) =>
                          shorter(remA, remB) match {
                            case None => Step.done[F, E, (A, B)]((resA, resB))
                            case Some(rem) => Step.early[F, E, (A, B)]((resA, resB), rem)
                          }
                        case (Some((resA, _)), None) => nextB.map((resA, _))
                        case (None, Some((resB, _))) => nextA.map((_, resB))
                        case _ => Step.cont(loop(nextA, nextB))
                      }
                    )
                  }
                )
            }
          )

          def onEnd: F[Step[F, E, (A, B)]] = F.flatMap(stepA.feed(Input.end))(
            _.bindF(a => F.map(stepB.feed(Input.end))(_.map((a, _))))
          )
        }
      )

    paired(stepA).bindF {
      case (pairA, nextA) =>
        paired(stepB).bindF {
          case (pairB, nextB) =>
            F.pure[Step[F, E, (A, B)]](
              (pairA, pairB) match {
                case (Some((resA, remA)), Some((resB, remB))) =>
                          shorter(remA, remB) match {
                            case None => Step.done[F, E, (A, B)]((resA, resB))
                            case Some(rem) => Step.early[F, E, (A, B)]((resA, resB), rem)
                          }

                case (Some((resA, _)), None) => nextB.map((resA, _))
                case (None, Some((resB, _))) => nextA.map((_, resB))
                case _ => Step.cont(loop(nextA, nextB))
              }
            )
        }
    }
  }
}