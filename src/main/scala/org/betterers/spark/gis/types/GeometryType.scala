package org.betterers.spark.gis.types

import java.io.CharArrayWriter

import com.esri.core.geometry._
import com.esri.core.geometry.ogc.OGCGeometry
import org.apache.spark.sql.types._
import org.codehaus.jackson.JsonFactory

import scala.util.parsing.json.JSONObject

/**
 * User defined type wrapping an ESRI [[Geometry]]
 *
 * @author drubbo <ubik@gamezoo.it>
 */
@SQLUserDefinedType(udt = classOf[GeometryType])
class GeometryType extends UserDefinedType[GeometryType] {

  private var value: Geometry = null
  private var srid: SpatialReference = null
  @transient
  private var ogc: OGCGeometry = null

  /**
   * Builds a [[GeometryType]] from an ESRI [[Geometry]] and an SRID
   * @param srid
   * @param geom
   */
  def this(srid: SpatialReference, geom: Geometry) = {
    this()
    this.value = geom
    this.srid = srid
    this.ogc = OGCGeometry.createFromEsriGeometry(geom, srid)
  }

  /**
   * Builds a [[GeometryType]] from an ESRI [[Geometry]] with default SRID
   * @param geom
   */
  def this(geom: Geometry) = {
    this(GeometryType.WGS84, geom)
  }

  /**
   * Builds a [[GeometryType]] of type point, line, or polyline, with an SRID
   * @param srid
   * @param points
   */
  def this(srid: SpatialReference, points: (Double, Double)*) = {
    this(srid, {
      def mkPoint(i: Integer) = new Point(points(i)._1, points(i)._2)
      def mkLine(from: Integer, to: Integer) = {
        val rt = new Line()
        rt.setStart(mkPoint(from))
        rt.setEnd(mkPoint(to))
        rt
      }
      points.size match {
        case 0 => throw new IllegalArgumentException("Invalid number of points: 0")
        case 1 => mkPoint(0)
        case 2 => new Polyline(mkPoint(0), mkPoint(1))
        case n =>
          val poly = new Polyline(mkPoint(0), mkPoint(1))
          var i = 2
          while (i < n) {
            poly.addSegment(mkLine(i - 1, i), false)
            i += 1
          }
          poly
      }
    })
  }

  /**
   * Builds a [[GeometryType]] of type point, line, or polyline, with default SRID
   * @param points
   */
  def this(points: (Double, Double)*) = {
    this(GeometryType.WGS84, points: _*)
  }

  override def toString: String = toGeoJson

  def toJson =
    if (value == null) null
    else GeometryEngine.geometryToJson(srid, value)

  def toGeoJson =
    if (value == null) null
    else {
      val op = OperatorFactoryLocal.getInstance().
        getOperator(Operator.Type.ExportToGeoJson).
        asInstanceOf[OperatorExportToGeoJson]
      op.execute(srid, value)
    }

  override def sqlType: DataType = StringType

  override def userClass: Class[GeometryType] = classOf[GeometryType]

  /**
   * Translates a [[GeometryType]] to a geoJson [[String]]
   * @param obj
   * @return
   */
  override def serialize(obj: Any): Any = {
    obj match {
      case g: GeometryType =>
        g.toGeoJson
      case x =>
        throw new IllegalArgumentException("Invalid GeometryType value to serialize: " + x)
    }
  }

  /**
   * Translates a [[GeometryType]] or [[String]] datum to a [[GeometryType]]
   * @param datum
   * @return
   */
  override def deserialize(datum: Any): GeometryType = {
    datum match {
      case g: GeometryType => g
      case s: String => GeometryType.fromGeoJson(s)
      case r: Map[_, _] =>
        val writer = new CharArrayWriter()
        val gen = new JsonFactory().createJsonGenerator(writer)

        def writeJson: Any => Unit = {
          case m: Map[_, _] =>
            gen.writeStartObject()
            m.foreach { kv =>
              gen.writeFieldName(kv._1.toString)
              writeJson(kv._2)
            }
            gen.writeEndObject()
          case a: Seq[_] =>
            gen.writeStartArray()
            a.foreach(writeJson)
            gen.writeEndArray()
          case x =>
            gen.writeObject(x)
        }

        writeJson(r)
        gen.flush()
        val json = writer.toString
        gen.close()
        println(">>>>>>>>>" + json)
        GeometryType.fromGeoJson(json)
      case x =>
        throw new IllegalArgumentException("Can't deserialize to GeometryType: " + x)
    }
  }
}

/**
 * Factory methods for [[GeometryType]]
 */
object GeometryType {

  val T = new GeometryType()

  private val WGS84 = SpatialReference.create(4326)

  /**
   * Build a [[GeometryType]] from a json string
   * @param json
   * @return
   */
  def fromJson(json: String): GeometryType = {
    val jsonParser = new JsonFactory().createJsonParser(json)
    jsonParser.nextToken()
    val geom = GeometryEngine.jsonToGeometry(jsonParser).getGeometry
    new GeometryType(geom)
  }

  /**
   * Build a [[GeometryType]] from a geoJson string
   * @param geoJson
   * @return
   */
  def fromGeoJson(geoJson: String): GeometryType = {
    val op = OperatorFactoryLocal.getInstance.
      getOperator(Operator.Type.ImportFromGeoJson).
      asInstanceOf[OperatorImportFromGeoJson]
    val geom = op.execute(0, Geometry.Type.Unknown, geoJson, null).getGeometry

    new GeometryType(geom)
  }
}
