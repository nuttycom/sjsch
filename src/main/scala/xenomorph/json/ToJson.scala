/*
 * Copyright (C) 2017 Kris Nuttycombe
 * All rights reserved.
 *
 * This file is part of the Scala Xenomorph library.
 *
 * GNU Lesser General Public License Usage
 * This file may be used under the terms of the GNU Lesser
 * General Public License version 3.0 as published by the Free Software
 * Foundation and appearing in the file LICENSE included in the
 * packaging of this file.  Please review the following information to
 * ensure the GNU Lesser General Public License version 3.0 requirements
 * will be met: https://www.gnu.org/licenses/lgpl-3.0.txt
 */
package xenomorph.json

import argonaut._
import argonaut.Json._

import scalaz.~>
import scalaz.State
import scalaz.State._
import scalaz.FreeAp
import scalaz.syntax.monad._
import scalaz.syntax.std.option._

import xenomorph._
import xenomorph.Schema._
import xenomorph.HFunctor._

trait ToJson[S[_]] {
  def serialize: S ~> (? => Json)
}

object ToJson {
  implicit def jSchemaToJson[P[_]: ToJson]: ToJson[Schema[P, ?]] = new ToJson[Schema[P, ?]] {
    def serialize = new (Schema[P, ?] ~> (? => Json)) {
      override def apply[I](schema: Schema[P, I]) = {
        HFix.cataNT[SchemaF[P, ?[_], ?], ? => Json](serializeAlg).apply(schema)
      }
    }
  }

  def serializeAlg[P[_]: ToJson]: HAlgebra[SchemaF[P, ?[_], ?], ? => Json] = 
    new HAlgebra[SchemaF[P, ?[_], ?], ? => Json] {
      def apply[I](schema: SchemaF[P, ? => Json, I]): I => Json = {
        schema match {
          case s: PrimSchema[P, ? => Json, I] => 
            implicitly[ToJson[P]].serialize(s.prim)

          case s: OneOfSchema[P, ? => Json, I] => 
            (value: I) => {
              val results = s.alts flatMap { 
                case alt: Alt[? => Json, I, i] => {
                  alt.prism.getOption(value).map(alt.base).toList map { json => 
                    jObject(JsonObject.single(alt.id, json))
                  }
                }
              } 

              results.head //yeah, I know
            }

          case s: RecordSchema[P, ? => Json, I] => 
            serializeObjF[P, I](s.props)
        }
      }
    }

  def serializeObjF[P[_]: ToJson, I](rb: FreeAp[PropSchema[I, ? => Json, ?], I]): I => Json = {
    (value: I) => jObject(
      rb.foldMap[State[JsonObject, ?]](
        new (PropSchema[I, ? => Json, ?] ~> State[JsonObject, ?]) {
          def apply[B](ps: PropSchema[I, ? => Json, B]): State[JsonObject, B] = {
            for {
              obj <- get
              _ <- ps match {
                case req: Required[I, ? => Json, i] => //(field, base, getter, _) => 
                  put(obj + (req.fieldName, req.base(req.getter.get(value))))

                case opt: Optional[I, ? => Json, i] =>
                  opt.getter.get(value).cata(
                    v => put(obj + (opt.fieldName, opt.base(v))),
                    ().pure[State[JsonObject, ?]]
                  )
              }
            } yield ps.getter.get(value)
          }
        }
      ).exec(JsonObject.empty)
    )
  }
}