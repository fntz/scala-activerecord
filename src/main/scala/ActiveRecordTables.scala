package com.github.aselab.activerecord

import aliases._
import org.squeryl._
import org.squeryl.PrimitiveTypeMode._
import mojolly.inflector.InflectorImports._
import squeryl.Implicits._

/**
 * Base class of database schema.
 */
trait ActiveRecordTables extends Schema {
  import ReflectionUtil._

  lazy val tableMap = {
    val c = classOf[ActiveRecord.HasAndBelongsToManyAssociation[_, _]]
    val map = collection.mutable.Map[String, Table[inner.IntermediateRecord]]()
    this.getFields[Table[ActiveRecordBase[_]]].map {f =>
      val clazz = getGenericTypes(f).last
      clazz.getDeclaredFields.foreach {f =>
        if (c.isAssignableFrom(f.getType)) {
          val List(c1, c2) = getGenericTypes(f)
          val tableName = tableNameFromClasses(c1, c2)
          if (!map.isDefinedAt(tableName)) {
            implicit val d = inner.IntermediateRecord.keyedEntityDef
            map(tableName) = super.table[inner.IntermediateRecord](tableName)
          }
        }
      }
      (clazz.getName, this.getValue[Table[AR]](f.getName))
    }.toMap ++ map
  }

  /** All tables */
  lazy val all = tableMap.values

  override def columnNameFromPropertyName(propertyName: String): String  =
    propertyName.underscore
    
  override def tableNameFromClass(c: Class[_]): String =
    c.getSimpleName.underscore.pluralize

  def tableNameFromClasses(c1: Class[_], c2: Class[_]): String =
    Seq(c1, c2).map(tableNameFromClass).sorted.mkString("_")

  def foreignKeyFromClass(c: Class[_]): String =
    c.getSimpleName.takeWhile(_ != '$').camelize + "Id"

  private def createTables = inTransaction {
    val isCreated = all.headOption.exists{ t =>
      val stat = Session.currentSession.connection.createStatement
      try {
        stat.execute("select 1 from " + t.name)
        true
      } catch {
        case e => false
      } finally {
        try { stat.close } catch {case e => }
      }
    }

    if (!isCreated) create
  }

  private var _initialized = false

  /** load configuration and then setup database and session */
  def initialize(implicit config: Map[String, Any] = Map()) {
    if (!_initialized) {
      Config.conf = loadConfig(config)

      SessionFactory.concreteFactory = Some(() => session)

      createTables
    }

    _initialized = true
  }

  /** cleanup database resources */
  def cleanup: Unit = Config.cleanup

  def loadConfig(config: Map[String, Any]): ActiveRecordConfig =
    new DefaultConfig(overrideSettings = config)

  def session: Session = {
    val s = Session.create(Config.connection, Config.adapter)
    s.setLogger(Config.logger.debug)
    s
  }

  /** drop and create table */
  def reset: Unit = inTransaction {
    drop
    create
  }

  private var _session: (Option[Session], Option[Session]) = (None, None)

  /** Set rollback point for test */
  def start {
    val oldSession = Session.currentSessionOption
    val newSession = SessionFactory.newSession
    oldSession.foreach(_.unbindFromCurrentThread)
    newSession.bindToCurrentThread
    val c = newSession.connection
    try {
      if (c.getAutoCommit) c.setAutoCommit(false)
    } catch { case e => }
    _session = (oldSession, Option(newSession))
  }

  /** Rollback to start point */
  def clean: Unit = _session match {
    case (oldSession, Some(newSession)) =>
      newSession.connection.rollback
      newSession.unbindFromCurrentThread
      oldSession.foreach(_.bindToCurrentThread)
      _session = (None, None)
    case _ =>
      throw ActiveRecordException.cannotCleanSession
  }

  def table[T <: AR]()(implicit m: Manifest[T]): Table[T] = {
    table(tableNameFromClass(m.erasure))(m)
  }

  def table[T <: AR](name: String)(implicit m: Manifest[T]): Table[T] = {
    val t = super.table[T](name)(m, dsl.keyedEntityDef(m))

    val c = classToCompanion(m.erasure).asInstanceOf[ActiveRecordBaseCompanion[_, T]]
    val fields = c.fieldInfo.values.toSeq
    import annotations._

    // schema declarations
    on(t)(r => declare(fields.collect {
      case f if f.hasAnnotation[Unique] =>
        f.toExpression(r.getValue[Any](f.name)).is(unique)
        
      case f if f.hasAnnotation[Confirmation] =>
        val name = Validator.confirmationFieldName(f.name, f.getAnnotation[Confirmation])
        f.toExpression(r.getValue[Any](name)).is(transient)
    }:_*))
    t
  }

}
