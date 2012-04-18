package com.github.aselab.activerecord

import org.specs2.mutable._
import org.specs2.specification._

case class User(name: String) extends ActiveRecord {
  val groupId: Option[Long] = None
  lazy val group = belongsTo[Group]
}

case class Group(name: String) extends ActiveRecord {
  lazy val users = hasMany[User]
}

object User extends ActiveRecordCompanion[User]
object Group extends ActiveRecordCompanion[Group]

object RelationSpec extends ActiveRecordSpecification {
  "ActiveRecord" should {
    "oneToMany relation" in { dsl.transaction {
      val g = Group("group1")
      g.save

      val u1 = User("user1")
      val u2 = User("user2")
      g.users.associate(u1)
      g.users.associate(u2)
      User("user3").save
      
      g.users must contain(u1, u2).only
      u1.group.headOption must beSome(g)
      u2.group.headOption must beSome(g)
    }}
  }
}
