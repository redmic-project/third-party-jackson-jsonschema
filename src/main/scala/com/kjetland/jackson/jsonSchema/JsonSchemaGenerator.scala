package com.kjetland.jackson.jsonSchema

import java.util
import java.util.Optional
import javax.validation.constraints._
import javax.annotation._

import scala.collection.JavaConverters._
import com.fasterxml.jackson.annotation.{JsonPropertyDescription, JsonSubTypes, JsonTypeInfo}
import com.fasterxml.jackson.core.JsonParser.NumberType
import com.fasterxml.jackson.databind.jsonFormatVisitors._
import com.fasterxml.jackson.databind._
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.introspect.{AnnotatedClass}
import com.fasterxml.jackson.databind.node.{ArrayNode, JsonNodeFactory, ObjectNode}
import com.kjetland.jackson.jsonSchema.annotations._
import org.slf4j.LoggerFactory

object JsonSchemaGenerator {
  val JSON_SCHEMA_DRAFT_4_URL = "http://json-schema.org/draft-04/schema#"
}

object JsonSchemaConfig {

  val vanillaJsonSchemaDraft4 = JsonSchemaConfig(
    autoGenerateTitleForProperties = false,
    defaultArrayFormat = None,
    useOneOfForOption = true,
    usePropertyOrdering = false,
    hidePolymorphismTypeProperty = false,
    disableWarnings = false,
    useMinLengthForNotNull = false,
    useTypeIdForDefinitionName = false,
    customType2FormatMapping = Map()
  )

  /**
    * Use this configuration if using the JsonSchema to generate HTML5 GUI, eg. by using https://github.com/jdorn/json-editor
    *
    * autoGenerateTitleForProperties - If property is named "someName", we will add {"title": "Some Name"}
    * defaultArrayFormat - this will result in a better gui than te default one.
    *
    */
  val html5EnabledSchema = JsonSchemaConfig(
    autoGenerateTitleForProperties = true,
    defaultArrayFormat = Some("table"),
    useOneOfForOption = true,
    usePropertyOrdering = true,
    hidePolymorphismTypeProperty = true,
    disableWarnings = false,
    useMinLengthForNotNull = true,
    useTypeIdForDefinitionName = false,
    customType2FormatMapping = Map[String, String](
      // Java7 dates
      "java.time.LocalDateTime" -> "datetime-local",
      "java.time.OffsetDateTime" -> "datetime",
      "java.time.LocalDate" -> "date",

      // Joda-dates
      "org.joda.time.LocalDate" -> "date"
    )
  )

  // Java-API
  def create(
              autoGenerateTitleForProperties: Boolean,
              defaultArrayFormat: Optional[String],
              useOneOfForOption: Boolean,
              usePropertyOrdering: Boolean,
              hidePolymorphismTypeProperty: Boolean,
              disableWarnings: Boolean,
              useMinLengthForNotNull: Boolean,
              useTypeIdForDefinitionName: Boolean,
              customType2FormatMapping: java.util.Map[String, String]
            ): JsonSchemaConfig = {

    import scala.collection.JavaConverters._

    JsonSchemaConfig(
      autoGenerateTitleForProperties,
      Option(defaultArrayFormat.orElse(null)),
      useOneOfForOption,
      usePropertyOrdering,
      hidePolymorphismTypeProperty,
      disableWarnings,
      useMinLengthForNotNull,
      useTypeIdForDefinitionName,
      customType2FormatMapping.asScala.toMap
    )
  }
}
  case class JsonSchemaConfig
  (
    autoGenerateTitleForProperties:Boolean,
    defaultArrayFormat:Option[String],
    useOneOfForOption:Boolean,
    usePropertyOrdering:Boolean,
    hidePolymorphismTypeProperty:Boolean,
    disableWarnings:Boolean,
    useMinLengthForNotNull:Boolean,
    useTypeIdForDefinitionName:Boolean,
    customType2FormatMapping:Map[String, String]
  )


  object JsonSchemaResources {

    val defaultResources = JsonSchemaResources(
      properties = Map()
    )

    def setResources(
        properties: java.util.Map[String, Object]
      ): JsonSchemaResources = {

      import scala.collection.JavaConverters._

      JsonSchemaResources(
        properties.asScala.mapValues(_.toString).toMap
      )
    }
  }

  case class JsonSchemaResources
  (
    properties:Map[String, String]
  )


/**
  * Json Schema Generator
  * @param rootObjectMapper pre-configured ObjectMapper
  * @param debug Default = false - set to true if generator should log some debug info while generating the schema
  * @param config default = vanillaJsonSchemaDraft4. Please use html5EnabledSchema if generating HTML5 GUI, e.g. using https://github.com/jdorn/json-editor
  * @param resources Set if use url label
  */
class JsonSchemaGenerator
(
  val rootObjectMapper: ObjectMapper,
  resources:JsonSchemaResources = JsonSchemaResources.defaultResources,
  debug:Boolean = false,
  config:JsonSchemaConfig = JsonSchemaConfig.vanillaJsonSchemaDraft4
) {

  // Java API
  def this(rootObjectMapper: ObjectMapper) = this(rootObjectMapper, JsonSchemaResources.defaultResources, false, JsonSchemaConfig.vanillaJsonSchemaDraft4)

  // Java API
  def this(rootObjectMapper: ObjectMapper, config:JsonSchemaConfig) = this(rootObjectMapper, JsonSchemaResources.defaultResources, false, config)

  // Java API
  def this(rootObjectMapper: ObjectMapper, resources: JsonSchemaResources) = this(rootObjectMapper, resources, false, JsonSchemaConfig.vanillaJsonSchemaDraft4)



  val log = LoggerFactory.getLogger(getClass)

  val dateFormatMapping = Map[String,String](
    // Java7 dates
    "java.time.LocalDateTime" -> "datetime-local",
    "java.time.OffsetDateTime" -> "datetime",
    "java.time.LocalDate" -> "date",

    // Joda-dates
    "org.joda.time.LocalDate" -> "date"
  )

  trait MySerializerProvider {
    var provider: SerializerProvider = null

    def getProvider: SerializerProvider = provider
    def setProvider(provider: SerializerProvider): Unit = this.provider = provider
  }

  trait EnumSupport {

    val _node: ObjectNode

    def enumTypes(enums: util.Set[String]): Unit = {
      // l(s"JsonStringFormatVisitor-enum.enumTypes: ${enums}")

      val enumValuesNode = JsonNodeFactory.instance.arrayNode()
      _node.set("enum", enumValuesNode)

      enums.asScala.foreach {
        enumValue =>
          enumValuesNode.add(enumValue)
      }
    }
  }

  private def setFormat(node:ObjectNode, format:String): Unit = {
    node.put("format", format)
  }


  case class DefinitionInfo(ref:Option[String], jsonObjectFormatVisitor: Option[JsonObjectFormatVisitor])

  // Class that manages creating new defenitions or getting $refs to existing definitions
  class DefinitionsHandler() {

    private var class2Ref = Map[Class[_], String]()
    private val definitionsNode = JsonNodeFactory.instance.objectNode()


    case class WorkInProgress(classInProgress:Class[_], nodeInProgress:ObjectNode)

    // Used when 'combining' multiple invocations to getOrCreateDefinition when processing polymorphism.
    var workInProgress:Option[WorkInProgress] = None

    def getDefinitionName (clazz:Class[_]) = { if (config.useTypeIdForDefinitionName) clazz.getName else clazz.getSimpleName }

    // Either creates new definitions or return $ref to existing one
    def getOrCreateDefinition(clazz:Class[_])(objectDefinitionBuilder:(ObjectNode) => Option[JsonObjectFormatVisitor]):DefinitionInfo = {

      class2Ref.get(clazz) match {
        case Some(ref) =>

          workInProgress match {
            case None =>
              DefinitionInfo(Some(ref), None)

            case Some(w) =>
              // this is a recursive polymorphism call
              if ( clazz != w.classInProgress) throw new Exception(s"Wrong class - working on ${w.classInProgress} - got $clazz")

              DefinitionInfo(None, objectDefinitionBuilder(w.nodeInProgress))
          }

        case None =>

          // new one - must build it
          var retryCount = 0
          var shortRef = getDefinitionName(clazz)
          var longRef = "#/definitions/" + shortRef
          while( class2Ref.values.toList.contains(longRef)) {
            retryCount = retryCount + 1
            shortRef = clazz.getSimpleName + "_" + retryCount
            longRef = "#/definitions/"+clazz.getSimpleName + "_" + retryCount
          }
          class2Ref = class2Ref + (clazz -> longRef)

          // create definition
          val node = JsonNodeFactory.instance.objectNode()

          // When processing polymorphism, we might get multiple recursive calls to getOrCreateDefinition - this is a wau to combine them
          workInProgress = Some(WorkInProgress(clazz, node))

          definitionsNode.set(shortRef, node)

          val jsonObjectFormatVisitor = objectDefinitionBuilder.apply(node)

          workInProgress = None

          DefinitionInfo(Some(longRef), jsonObjectFormatVisitor)
      }
    }

    def getFinalDefinitionsNode():Option[ObjectNode] = {
      if (class2Ref.isEmpty) None else Some(definitionsNode)
    }

  }

  class MyJsonFormatVisitorWrapper
  (
    objectMapper: ObjectMapper,
    level:Int = 0,
    val node: ObjectNode = JsonNodeFactory.instance.objectNode(),
    val definitionsHandler:DefinitionsHandler,
    currentProperty:Option[BeanProperty] // This property may represent the BeanProperty when we're directly processing beneath the property
  ) extends JsonFormatVisitorWrapper with MySerializerProvider {

      def l(s: => String): Unit = {
        if (!debug) return

        var indent = ""
        for (i <- 0 until level) {
          indent = indent + "  "
        }
        println(indent + s)
      }


      def createChild(childNode: ObjectNode, currentProperty: Option[BeanProperty]): MyJsonFormatVisitorWrapper = {
        new MyJsonFormatVisitorWrapper(objectMapper, level + 1, node = childNode, definitionsHandler = definitionsHandler, currentProperty = currentProperty)
      }

      override def expectStringFormat(_type: JavaType)

      =
      {
        if (propertyIsNotIgnored(currentProperty)) {
          l(s"expectStringFormat - _type: ${_type}")

          setType(node, currentProperty, "string")
          //node.put("type", "string")

          // Check if we should include minLength and/or maxLength
          case class MinAndMaxLength(minLength: Option[Int], maxLength: Option[Int])

          val minAndMaxLength: Option[MinAndMaxLength] = currentProperty.flatMap {
            p =>
              // Look for @Pattern
              Option(p.getAnnotation(classOf[Pattern])).map {
                pattern =>
                  node.put("pattern", pattern.regexp())
              }

              // Look for @JsonSchemaDefault
              Option(p.getAnnotation(classOf[JsonSchemaDefault])).map {
                defaultValue =>
                  node.put("default", defaultValue.value())
              }

              // Look for @Size
              Option(p.getAnnotation(classOf[Size]))
                .map {
                  size =>
                    (size.min(), size.max()) match {
                      case (0, max) => MinAndMaxLength(None, Some(max))
                      case (min, Integer.MAX_VALUE) => MinAndMaxLength(Some(min), None)
                      case (min, max) => MinAndMaxLength(Some(min), Some(max))
                    }
                }
                .orElse {
                  // We did not find @Size - check if we should include it anyway
                  if (config.useMinLengthForNotNull) {
                    Option(p.getAnnotation(classOf[NotNull])).map {
                      notNull =>
                        MinAndMaxLength(Some(1), None)
                    }
                  } else None
                }
          }

          minAndMaxLength.map {
            minAndMax: MinAndMaxLength =>
              minAndMax.minLength.map(length => node.put("minLength", length))
              minAndMax.maxLength.map(length => node.put("maxLength", length))
          }

          new JsonStringFormatVisitor with EnumSupport {
            val _node = node

            override def format(format: JsonValueFormat): Unit = {
              setFormat(node, format.toString)
            }
          }
        } else {
          null
        }

      }

      override def expectArrayFormat(_type: JavaType)

      =
      {

        if (propertyIsNotIgnored(currentProperty)) {
          l(s"expectArrayFormat - _type: ${_type}")

          setType(node, currentProperty, "array")
          //node.put("type", "array")

          if (propertyIsUniqueItemsByRequiredProperties(currentProperty))
            node.put("uniqueItemsByRequiredProperties", true)

          // Añade Etiquetas de maxItems y minItems en función de @Size para arrays
          // TODO: Pasar a una función para refactorizar con String
          case class MinAndMaxSize(minSize: Option[Int], maxSize: Option[Int])

          val minAndMaxSize: Option[MinAndMaxSize] = currentProperty.flatMap {

            p =>
              Option(p.getAnnotation(classOf[Size]))
                .map {
                  size =>
                    (size.min(), size.max()) match {
                      case (0, max) => MinAndMaxSize(None, Some(max))
                      case (min, Integer.MAX_VALUE) => MinAndMaxSize(Some(min), None)
                      case (min, max) => MinAndMaxSize(Some(min), Some(max))
                    }
                }
          }

          minAndMaxSize.map {
            minAndMax: MinAndMaxSize =>
              minAndMax.minSize.map(length => node.put("minItems", length))
              minAndMax.maxSize.map(length => node.put("maxItems", length))
          }
          ////////////////////////////////
          config.defaultArrayFormat.foreach {
            format => setFormat(node, format)
          }

          node.put("uniqueItems", true)

          val itemsNode = JsonNodeFactory.instance.objectNode()
          node.set("items", itemsNode)

          // We get improved result while processing scala-collections by getting elementType this way
          // instead of using the one which we receive in JsonArrayFormatVisitor.itemsFormat
          // This approach also works for Java
          val preferredElementType: JavaType = _type.getContentType

          new JsonArrayFormatVisitor with MySerializerProvider {
            override def itemsFormat(handler: JsonFormatVisitable, _elementType: JavaType): Unit = {
              l(s"expectArrayFormat - handler: $handler - elementType: ${_elementType} - preferredElementType: $preferredElementType")
              objectMapper.acceptJsonFormatVisitor(preferredElementType, createChild(itemsNode, currentProperty = None))
            }

            override def itemsFormat(format: JsonFormatTypes): Unit = {
              l(s"itemsFormat - format: $format")
              itemsNode.put("type", format.value())
            }
          }
        }
        else {
          null
        }
      }

      override def expectNumberFormat(_type: JavaType)

      =
      {
        if (propertyIsNotIgnored(currentProperty)) {
          l("expectNumberFormat")

          setType(node, currentProperty, "number")
          //node.put("type", "number")

          // Look for @Min, @Max => minumum, maximum
          currentProperty.map {
            p =>
              Option(p.getAnnotation(classOf[Min])).map {
                min =>
                  node.put("minimum", min.value())
              }

              Option(p.getAnnotation(classOf[Max])).map {
                max =>
                  node.put("maximum", max.value())
              }

              // Look for @JsonSchemaDefault
              Option(p.getAnnotation(classOf[JsonSchemaDefault])).map {
                defaultValue =>
                  node.put("default", defaultValue.value().toLong)
              }
          }

          new JsonNumberFormatVisitor with EnumSupport {
            val _node = node

            override def numberType(_type: NumberType): Unit = l(s"JsonNumberFormatVisitor.numberType: ${_type}")

            override def format(format: JsonValueFormat): Unit = {
              setFormat(node, format.toString)
            }
          }
        } else {
          null
        }
      }

      override def expectAnyFormat(_type: JavaType)

      =
      {
        if (propertyIsNotIgnored(currentProperty)) {
          // Comprueba si se trata de una geometría, ya que inicialmente no conoce el formato pero se añade
          // compatibilidad
          expectGeometry(_type)

          new JsonAnyFormatVisitor {
          }
        } else {
          null
        }
      }


      def expectGeometry(_type: JavaType)

      =
      {
        // Crea esquema para geometrías, haciendo compatible con vividsolutions
        val superClass = _type.getSuperClass
        if (_type.getRawClass.getName.contains("vividsolutions")) {

          setType(node, currentProperty, "object")
          //node.put("type", "object")
          node.put("additionalProperties", false)

          val propertiesNode = JsonNodeFactory.instance.objectNode()
          node.set("properties", propertiesNode)

          getRequiredArrayNode(node).add("type")
          getRequiredArrayNode(node).add("coordinates")

          val typeNode = JsonNodeFactory.instance.objectNode()
          typeNode.put("type", "string")
          val enumType = JsonNodeFactory.instance.arrayNode()
          typeNode.set("enum", enumType)

          val coordinatesNode = JsonNodeFactory.instance.objectNode()
          coordinatesNode.put("type", "array")

          //Esquema de Point
          val itemsNodePoint = JsonNodeFactory.instance.objectNode()
          itemsNodePoint.put("type", "number")
          itemsNodePoint.put("maximun", 9000000000000000L)
          itemsNodePoint.put("minimun", -9000000000000000L)

          // Esquema de LineString
          val itemsNodeLineString = JsonNodeFactory.instance.objectNode()
          itemsNodeLineString.put("type", "array")
          itemsNodeLineString.put("items", itemsNodePoint)
          itemsNodeLineString.put("minItems", "2")

          // Esquema de LineString para un polígono
          val itemsNodeLinearRings = JsonNodeFactory.instance.objectNode()
          itemsNodeLinearRings.put("type", "array")
          itemsNodeLinearRings.put("items", itemsNodePoint)
          itemsNodeLinearRings.put("minItems", "4")

          // Esquema de Polygon
          val itemsNodePolygon = JsonNodeFactory.instance.objectNode()
          itemsNodePolygon.put("type", "array")
          itemsNodePolygon.put("items", itemsNodeLinearRings)
          itemsNodePolygon.put("minItems", "1")

          if (_type.getRawClass.getName.contains("Point")) {
            enumType.add("Point")
            coordinatesNode.set("items", itemsNodePoint)
            coordinatesNode.put("minItems", 2)
            coordinatesNode.put("maxItems", 2)
          } else if (_type.getRawClass.getName.contains("MultiLineString")) {
            enumType.add("MultiLineString")
            coordinatesNode.set("items", itemsNodeLineString)
            coordinatesNode.put("minItems", 1)
          } else if (_type.getRawClass.getName.contains("LineString")) {
            enumType.add("LineString")
            coordinatesNode.set("items", itemsNodeLineString)
            coordinatesNode.put("minItems", 2)
          } else if (_type.getRawClass.getName.contains("MultiPolygon")) {
            enumType.add("MultiPolygon")
            coordinatesNode.set("items", itemsNodePolygon)
            coordinatesNode.put("minItems", 1)
          } else if (_type.getRawClass.getName.contains("Polygon")) {
            enumType.add("Polygon")
            coordinatesNode.set("items", itemsNodeLinearRings)
            coordinatesNode.put("minItems", 1)
          }

          propertiesNode.set("type", typeNode)
          propertiesNode.set("coordinates", coordinatesNode)
        } else {
          if (!config.disableWarnings) {
            log.warn(s"Not able to generate jsonSchema-info for type: ${_type} - probably using custom serializer which does not override acceptJsonFormatVisitor")
          }

        }
      }

      override def expectIntegerFormat(_type: JavaType)
      =
      {
        if (propertyIsNotIgnored(currentProperty)) {
          l("expectIntegerFormat")

          setType(node, currentProperty, "integer")
          //node.put("type", "integer")

          // Look for @Min, @Max => minumum, maximum
          currentProperty.map {
            p =>
              Option(p.getAnnotation(classOf[Min])).map {
                min =>
                  node.put("minimum", min.value())
              }

              Option(p.getAnnotation(classOf[Max])).map {
                max =>
                  node.put("maximum", max.value())
              }

              // Look for @JsonSchemaDefault
              Option(p.getAnnotation(classOf[JsonSchemaDefault])).map {
                defaultValue =>
                  node.put("default", defaultValue.value().toInt)
              }
          }


          new JsonIntegerFormatVisitor with EnumSupport {
            val _node = node

            override def numberType(_type: NumberType): Unit = l(s"JsonIntegerFormatVisitor.numberType: ${_type}")

            override def format(format: JsonValueFormat): Unit = {
              setFormat(node, format.toString)
            }
          }
        }
        else {
          null
        }
      }

      override def expectNullFormat(_type: JavaType)

      =
      {
        l(s"expectNullFormat - _type: ${_type}")
        new JsonNullFormatVisitor {}
      }


      override def expectBooleanFormat(_type: JavaType)
      =
      {
        if (propertyIsNotIgnored(currentProperty)) {
          l("expectBooleanFormat")

          setType(node, currentProperty, "boolean")
          //node.put("type", "boolean")

          currentProperty.map {
            p =>
              // Look for @JsonSchemaDefault
              Option(p.getAnnotation(classOf[JsonSchemaDefault])).map {
                defaultValue =>
                  node.put("default", defaultValue.value().toBoolean)
              }
          }

          new JsonBooleanFormatVisitor with EnumSupport {
            val _node = node

            override def format(format: JsonValueFormat): Unit = {
              setFormat(node, format.toString)
            }
          }
        } else {
          null
        }
      }

      override def expectMapFormat(_type: JavaType)

      =
      {
        if (propertyIsNotIgnored(currentProperty)) {
          l(s"expectMapFormat - _type: ${_type}")

          // There is no way to specify map in jsonSchema,
          // So we're going to treat it as type=object with additionalProperties = true,
          // so that it can hold whatever the map can hold


          setType(node, currentProperty, "object")
          //node.put("type", "object")
          node.put("additionalProperties", true)


          new JsonMapFormatVisitor with MySerializerProvider {
            override def keyFormat(handler: JsonFormatVisitable, keyType: JavaType): Unit = {
              l(s"JsonMapFormatVisitor.keyFormat handler: $handler - keyType: $keyType")
            }

            override def valueFormat(handler: JsonFormatVisitable, valueType: JavaType): Unit = {
              l(s"JsonMapFormatVisitor.valueFormat handler: $handler - valueType: $valueType")
            }
          }
        }
        else {
          null
        }
      }

      private def getRequiredArrayNode(objectNode: ObjectNode): ArrayNode

      =
      {
        Option(objectNode.get("required")).map(_.asInstanceOf[ArrayNode]).getOrElse {
          val rn = JsonNodeFactory.instance.arrayNode()
          objectNode.set("required", rn)
          rn
        }
      }

      case class PolymorphismInfo(typePropertyName: String, subTypeName: String)

      private def extractPolymorphismInfo(_type: JavaType): Option[PolymorphismInfo]

      =
      {
        // look for @JsonTypeInfo
        val ac = AnnotatedClass.construct(_type, objectMapper.getDeserializationConfig())
        Option(ac.getAnnotations.get(classOf[JsonTypeInfo])).map {
          jsonTypeInfo =>
            if (jsonTypeInfo.include() != JsonTypeInfo.As.PROPERTY) throw new Exception("We only support polymorphism using jsonTypeInfo.include() == JsonTypeInfo.As.PROPERTY")
            if (jsonTypeInfo.use != JsonTypeInfo.Id.NAME) throw new Exception("We only support polymorphism using jsonTypeInfo.use == JsonTypeInfo.Id.NAME")


            val propertyName = jsonTypeInfo.property()

            // Must find out what this current class should be called
            val subTypeName: String = objectMapper.getSubtypeResolver.collectAndResolveSubtypesByClass(objectMapper.getDeserializationConfig, ac).asScala.toList
              .filter(_.getType == _type.getRawClass)
              .find(p => true) // find first
              .get.getName

            PolymorphismInfo(propertyName, subTypeName)
        }
      }

      override def expectObjectFormat(_type: JavaType)
      =
      {

          val ac = AnnotatedClass.construct(_type, objectMapper.getDeserializationConfig())
          val resolvedSubTypes = objectMapper.getSubtypeResolver.collectAndResolveSubtypesByClass(objectMapper.getDeserializationConfig, ac).asScala.toList
          val subTypes: List[Class[_]] = resolvedSubTypes.map(_.getType)
            .filter(c => _type.getRawClass.isAssignableFrom(c) && _type.getRawClass != c)

          if (subTypes.nonEmpty) {
            //l(s"polymorphism - subTypes: $subTypes")

            val anyOfArrayNode = JsonNodeFactory.instance.arrayNode()
            node.set("oneOf", anyOfArrayNode)

            subTypes.foreach {
              subType: Class[_] =>
                l(s"polymorphism - subType: $subType")

                val definitionInfo: DefinitionInfo = definitionsHandler.getOrCreateDefinition(subType) {
                  objectNode =>

                    val childVisitor = createChild(objectNode, currentProperty = None)
                    objectMapper.acceptJsonFormatVisitor(subType, childVisitor)

                    None
                }

                val thisOneOfNode = JsonNodeFactory.instance.objectNode()
                thisOneOfNode.put("$ref", definitionInfo.ref.get)
                anyOfArrayNode.add(thisOneOfNode)
            }

            null // Returning null to stop jackson from visiting this object since we have done it manually

          } else {

              val objectBuilder: ObjectNode => Option[JsonObjectFormatVisitor] = {
                thisObjectNode: ObjectNode =>

                  //thisObjectNode.put("type", "object")
                  //thisObjectNode.put("additionalProperties", false)

                  // If class is annotated with JsonSchemaFormat, we should add it
                  val ac = AnnotatedClass.construct(_type, objectMapper.getDeserializationConfig())
                  resolvePropertyFormat(_type, objectMapper).foreach {
                    format =>
                      setFormat(thisObjectNode, format)
                  }

                  if (ac.getAnnotation(classOf[JsonSchemaNotNull]) != null) {
                    thisObjectNode.put("type", "object")
                  } else {
                    setType(thisObjectNode, null, "object")
                  }

                  // If class is annotated with JsonSchemaDescription, we should add it
                  Option(ac.getAnnotations.get(classOf[JsonSchemaDescription])).map(_.value())
                    .orElse(Option(ac.getAnnotations.get(classOf[JsonPropertyDescription])).map(_.value))
                    .foreach {
                      description: String =>
                        thisObjectNode.put("description", description)
                    }

                  // If class is annotated with JsonSchemaTitle, we should add it
                  Option(ac.getAnnotations.get(classOf[JsonSchemaTitle])).map(_.value()).foreach {
                    title =>
                      thisObjectNode.put("title", title)
                  }

                  val propertiesNode = JsonNodeFactory.instance.objectNode()
                  thisObjectNode.set("properties", propertiesNode)

                  extractPolymorphismInfo(_type).map {
                    case pi: PolymorphismInfo =>
                      // This class is a child in a polymorphism config..
                      // Set the title = subTypeName
                      thisObjectNode.put("title", pi.subTypeName)

                      // must inject the 'type'-param and value as enum with only one possible value
                      val enumValuesNode = JsonNodeFactory.instance.arrayNode()
                      enumValuesNode.add(pi.subTypeName)

                      val enumObjectNode = JsonNodeFactory.instance.objectNode()
                      enumObjectNode.put("type", "string")
                      enumObjectNode.set("enum", enumValuesNode)
                      enumObjectNode.put("default", pi.subTypeName)

                      if (config.hidePolymorphismTypeProperty) {
                        // Make sure the editor hides this polymorphism-specific property
                        val optionsNode = JsonNodeFactory.instance.objectNode()
                        enumObjectNode.set("options", optionsNode)
                        optionsNode.put("hidden", true)
                      }

                      propertiesNode.set(pi.typePropertyName, enumObjectNode)

                      getRequiredArrayNode(thisObjectNode).add(pi.typePropertyName)

                  }

                  Some(new JsonObjectFormatVisitor with MySerializerProvider {

                    // Used when rendering schema using propertyOrdering as specified here:
                    // https://github.com/jdorn/json-editor#property-ordering
                    var nextPropertyOrderIndex = 1

                    def myPropertyHandler(propertyName: String, propertyType: JavaType, prop: Option[BeanProperty], jsonPropertyRequired: Boolean): Unit = {

                      if (propertyIsNotIgnored(prop)) {

                        l(s"JsonObjectFormatVisitor - ${propertyName}: ${propertyType}")
                        if (propertiesNode.get(propertyName) != null) {
                          if (!config.disableWarnings) {
                            log.warn(s"Ignoring property '$propertyName' in $propertyType since it has already been added, probably as type-property using polymorphism")
                          }
                          return
                        }


                        // Need to check for Option/Optional-special-case before we know what node to use here.

                        case class PropertyNode(main: ObjectNode, meta: ObjectNode)

                        val thisPropertyNode: PropertyNode = {
                          val thisPropertyNode = JsonNodeFactory.instance.objectNode()
                          propertiesNode.set(propertyName, thisPropertyNode)

                          if (config.usePropertyOrdering) {
                            thisPropertyNode.put("propertyOrder", nextPropertyOrderIndex)
                            nextPropertyOrderIndex = nextPropertyOrderIndex + 1
                          }

                          // Check for Option/Optional-special-case
                          if (config.useOneOfForOption &&
                            (classOf[Option[_]].isAssignableFrom(propertyType.getRawClass)
                              || classOf[Optional[_]].isAssignableFrom(propertyType.getRawClass))) {
                            // Need to special-case for property using Option/Optional
                            // Should insert oneOf between 'real one' and 'null'
                            val oneOfArray = JsonNodeFactory.instance.arrayNode()
                            thisPropertyNode.set("oneOf", oneOfArray)


                            // Create the one used when Option is empty
                            val oneOfNull = JsonNodeFactory.instance.objectNode()
                            oneOfNull.put("type", "null")
                            oneOfNull.put("title", "Not included")
                            oneOfArray.add(oneOfNull)

                            // Create the one used when Option is defined with the real value
                            val oneOfReal = JsonNodeFactory.instance.objectNode()
                            oneOfArray.add(oneOfReal)

                            // Return oneOfReal which, from now on, will be used as the node representing this property
                            PropertyNode(oneOfReal, thisPropertyNode)

                          } else {
                            // Not special-casing - using thisPropertyNode as is
                            PropertyNode(thisPropertyNode, thisPropertyNode)
                          }
                        }

                        // Continue processing this property

                        val childVisitor = createChild(thisPropertyNode.main, currentProperty = prop)

                        if ((classOf[Option[_]].isAssignableFrom(propertyType.getRawClass) || classOf[Optional[_]].isAssignableFrom(propertyType.getRawClass)) && propertyType.containedTypeCount() >= 1) {

                          // Property is scala Option or Java Optional.
                          //
                          // Due to Java's Type Erasure, the type behind Option is lost.
                          // To workaround this, we use the same workaround as jackson-scala-module described here:
                          // https://github.com/FasterXML/jackson-module-scala/wiki/FAQ#deserializing-optionint-and-other-primitive-challenges

                          val optionType: JavaType = resolveType(propertyType, prop, objectMapper)

                          objectMapper.acceptJsonFormatVisitor(optionType, childVisitor)

                        } else {
                          objectMapper.acceptJsonFormatVisitor(propertyType, childVisitor)
                        }

                        // Check if we should set this property as required
                        val rawClass = propertyType.getRawClass
                        val requiredProperty: Boolean = if (rawClass.isPrimitive) {
                          // primitive boolean MUST have a value
                          true
                        } else if (jsonPropertyRequired) {
                          // @JsonPropertyRequired is set to true
                          true
                        } else if (prop.isDefined && prop.get.getAnnotation(classOf[NotNull]) != null) {
                          true
                        } else {
                          false
                        }

                        if (requiredProperty) {
                          getRequiredArrayNode(thisObjectNode).add(propertyName)
                        }

                        prop.flatMap(resolvePropertyFormat(_)).foreach {
                          format =>
                            setFormat(thisPropertyNode.main, format)
                        }

                        // Optionally add description
                        prop.flatMap {
                          p: BeanProperty =>
                            Option(p.getAnnotation(classOf[JsonSchemaDescription])).map(_.value())
                              .orElse(Option(p.getAnnotation(classOf[JsonPropertyDescription])).map(_.value()))
                        }.map {
                          description =>
                            thisPropertyNode.meta.put("description", description)
                        }

                        // Optionally add title
                        prop.flatMap {
                          p: BeanProperty =>
                            Option(p.getAnnotation(classOf[JsonSchemaTitle]))
                        }.map(_.value())
                          .orElse {
                            if (config.autoGenerateTitleForProperties) {
                              // We should generate 'pretty-name' based on propertyName
                              Some(generateTitleFromPropertyName(propertyName))
                            } else None
                          }
                          .map {
                            title =>
                              thisPropertyNode.meta.put("title", title)
                          }

                        // Optionally add default
                        prop.flatMap {
                          p: BeanProperty =>
                            Option(p.getAnnotation(classOf[JsonSchemaDefault])).map(_.value())
                        }.map {
                            defe =>
                              thisPropertyNode.meta.put("default", defe)
                          }

                        // Optionally add url
                        prop.flatMap {
                          p: BeanProperty =>
                            Option(p.getAnnotation(classOf[JsonSchemaUrl])).map(_.value())
                        }.map {
                          url =>
                            setType(thisPropertyNode.meta, prop, "integer")
                            //thisPropertyNode.meta.put("type", "integer")
                            thisPropertyNode.meta.put("url", resources.properties.get(url).getOrElse("none"))
                            thisPropertyNode.meta.remove("$ref")
                            removeDefinition(propertyType)
                        }

                      }
                      else {
                        removeDefinition(propertyType)
                      }
                    }

                    // Elimina la definición cuando se trata de una relación o se ha ignorado la propiedad
                    def removeDefinition(propertyType: JavaType): Unit = {
                      // Cuando se trata de un jsonSchemaIgnore si se ha metido un en definitions se elimina
                      val definitionNode = definitionsHandler.getFinalDefinitionsNode().getOrElse(null)
                      if (definitionNode != null) {
                        definitionNode.remove(propertyType.getRawClass.getName)
                        definitionNode.remove(propertyType.getRawClass.getSimpleName)
                      }
                    }
                    override def optionalProperty(prop: BeanProperty): Unit = {
                      l(s"JsonObjectFormatVisitor.optionalProperty: prop:${prop}")
                      myPropertyHandler(prop.getName, prop.getType, Some(prop), jsonPropertyRequired = false)
                    }

                    override def optionalProperty(name: String, handler: JsonFormatVisitable, propertyTypeHint: JavaType): Unit = {
                      l(s"JsonObjectFormatVisitor.optionalProperty: name:${name} handler:${handler} propertyTypeHint:${propertyTypeHint}")
                      myPropertyHandler(name, propertyTypeHint, None, jsonPropertyRequired = false)
                    }

                    override def property(prop: BeanProperty): Unit = {
                      l(s"JsonObjectFormatVisitor.property: prop:${prop}")
                      myPropertyHandler(prop.getName, prop.getType, Some(prop), jsonPropertyRequired = true)
                    }

                    override def property(name: String, handler: JsonFormatVisitable, propertyTypeHint: JavaType): Unit = {
                      l(s"JsonObjectFormatVisitor.property: name:${name} handler:${handler} propertyTypeHint:${propertyTypeHint}")
                      myPropertyHandler(name, propertyTypeHint, None, jsonPropertyRequired = true)
                    }
                  })
              }


              if (level == 0) {
                // This is the first level - we must not use definitions
                objectBuilder(node).orNull
              } else {
                val definitionInfo: DefinitionInfo = definitionsHandler.getOrCreateDefinition(_type.getRawClass)(objectBuilder)

                definitionInfo.ref.foreach {
                  r =>
                    // Must add ref to def at "this location"
                    node.put("$ref", r)
                }

                definitionInfo.jsonObjectFormatVisitor.orNull
              }
          }
      }

      // Añade a la propiedad el tipo null en caso de que no tenga la etiqueta notNull
      def setType(node: ObjectNode, prop: Option[BeanProperty], typePrimary: String)
      =
      {
        if (prop == null || propertyIsNullable(prop)) {
          val types = JsonNodeFactory.instance.arrayNode()
          types.add(typePrimary)
          types.add("null")
          node.set("type", types)
        }
        else
          node.put("type", typePrimary)
      }

      // comprueba si el valor de la propiedad puede ser nulo (no tiene la etiqueta notNull)
      def propertyIsNullable(prop: Option[BeanProperty])
      =
      {
        (prop != null && !prop.isEmpty && prop.get.getAnnotation(classOf[NotNull]) == null)
      }

      // Comprueba si la propiedad debe ser ignorada por introducir etiqueta @JsonSchemaIgnore
      def propertyIsNotIgnored(prop: Option[BeanProperty])
      =
      {
        (prop.isEmpty || prop.get.getAnnotation(classOf[JsonSchemaIgnore]) == null)
      }

      // Comprueba si se debe añadir la propiedad uniqueItemsByRequiredProperties
      def propertyIsUniqueItemsByRequiredProperties(prop: Option[BeanProperty])
      =
      {
        (prop != null && !prop.isEmpty && prop.get.getAnnotation(classOf[JsonSchemaUniqueItemsByRequiredProperties]) != null)
      }
  }

  def generateTitleFromPropertyName(propertyName:String):String = {
    // Code found here: http://stackoverflow.com/questions/2559759/how-do-i-convert-camelcase-into-human-readable-names-in-java
    val s = propertyName.replaceAll(
      String.format("%s|%s|%s",
        "(?<=[A-Z])(?=[A-Z][a-z])",
        "(?<=[^A-Z])(?=[A-Z])",
        "(?<=[A-Za-z])(?=[^A-Za-z])"
      ),
      " "
    )

    // Make the first letter uppercase
    s.substring(0,1).toUpperCase() + s.substring(1)
  }

  def resolvePropertyFormat(_type: JavaType, objectMapper:ObjectMapper):Option[String] = {
    val ac = AnnotatedClass.construct(_type, objectMapper.getDeserializationConfig())
    resolvePropertyFormat(Option(ac.getAnnotation(classOf[JsonSchemaFormat])), _type.getRawClass.getName)
  }

  def resolvePropertyFormat(prop: BeanProperty):Option[String] = {
    // Prefer format specified in annotation
    resolvePropertyFormat(Option(prop.getAnnotation(classOf[JsonSchemaFormat])), prop.getType.getRawClass.getName)
  }

  def resolvePropertyFormat(jsonSchemaFormatAnnotation:Option[JsonSchemaFormat], rawClassName:String):Option[String] = {
    // Prefer format specified in annotation
    jsonSchemaFormatAnnotation.map {
      jsonSchemaFormat =>
        jsonSchemaFormat.value()
    }.orElse {
      config.customType2FormatMapping.get(rawClassName)
    }
  }

  def resolveType(propertyType:JavaType, prop: Option[BeanProperty], objectMapper: ObjectMapper):JavaType = {
    val containedType = propertyType.containedType(0)

    if ( containedType.getRawClass == classOf[Object] ) {
      // try to resolve it via @JsonDeserialize as described here: https://github.com/FasterXML/jackson-module-scala/wiki/FAQ#deserializing-optionint-and-other-primitive-challenges
      prop.flatMap {
        p:BeanProperty =>
          Option(p.getAnnotation(classOf[JsonDeserialize]))
      }.flatMap {
        jsonDeserialize:JsonDeserialize =>
          Option(jsonDeserialize.contentAs()).map {
            clazz =>
              objectMapper.getTypeFactory.uncheckedSimpleType(clazz)
          }
      }.getOrElse( {
        if (!config.disableWarnings) {
          log.warn(s"$prop - Contained type is java.lang.Object and we're unable to extract its Type using fallback-approach looking for @JsonDeserialize")
        }
        containedType
      })

    } else {
      // use containedType as is
      containedType
    }
  }

  def generateJsonSchema[T <: Any](clazz: Class[T]): JsonNode = generateJsonSchema(clazz, None, None)
  def generateJsonSchema[T <: Any](clazz: Class[T], title:String, description:String): JsonNode = generateJsonSchema(clazz, Option(title), Option(description))
  def generateJsonSchema[T <: Any](clazz: Class[T], title:Option[String], description:Option[String]): JsonNode = {

    val rootNode = JsonNodeFactory.instance.objectNode()

    // Specify that this is a v4 json schema
    rootNode.put("$schema", JsonSchemaGenerator.JSON_SCHEMA_DRAFT_4_URL)
    //rootNode.put("id", "http://my.site/myschema#")

    // Add schema title
    title.orElse {
      Some(generateTitleFromPropertyName(clazz.getSimpleName))
    }.flatMap {
      title =>
        // Skip it if specified to empty string
        if ( title.isEmpty) None else Some(title)
    }.map {
      title =>
        rootNode.put("title", title)
        // If root class is annotated with @JsonSchemaTitle, it will later override this title
    }

    // Maybe set schema description
    description.map {
      d =>
        rootNode.put("description", d)
        // If root class is annotated with @JsonSchemaDescription, it will later override this description
    }

    val definitionsHandler = new DefinitionsHandler
    val rootVisitor = new MyJsonFormatVisitorWrapper(rootObjectMapper, node = rootNode, definitionsHandler = definitionsHandler, currentProperty = None)
    rootObjectMapper.acceptJsonFormatVisitor(clazz, rootVisitor)

    definitionsHandler.getFinalDefinitionsNode().foreach {
      definitionsNode => rootNode.set("definitions", definitionsNode)
    }

    rootNode
  }
}
