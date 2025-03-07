/*
 * Copyright 2020 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 *
 */

package io.confluent.kafka.schemaregistry.protobuf;

import static com.squareup.wire.schema.internal.UtilKt.MAX_TAG_VALUE;
import static io.confluent.kafka.schemaregistry.protobuf.ProtobufSchema.CONFLUENT_PREFIX;
import static io.confluent.kafka.schemaregistry.protobuf.ProtobufSchema.DEFAULT_LOCATION;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Ascii;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;

import com.squareup.wire.schema.Field.Label;
import com.squareup.wire.schema.ProtoType;
import com.squareup.wire.schema.internal.parser.EnumConstantElement;
import com.squareup.wire.schema.internal.parser.EnumElement;
import com.squareup.wire.schema.internal.parser.ExtendElement;
import com.squareup.wire.schema.internal.parser.ExtensionsElement;
import com.squareup.wire.schema.internal.parser.FieldElement;
import com.squareup.wire.schema.internal.parser.GroupElement;
import com.squareup.wire.schema.internal.parser.MessageElement;
import com.squareup.wire.schema.internal.parser.OneOfElement;
import com.squareup.wire.schema.internal.parser.OptionElement;
import com.squareup.wire.schema.internal.parser.OptionElement.Kind;
import com.squareup.wire.schema.internal.parser.ProtoFileElement;
import com.squareup.wire.schema.internal.parser.ReservedElement;
import com.squareup.wire.schema.internal.parser.RpcElement;
import com.squareup.wire.schema.internal.parser.ServiceElement;
import com.squareup.wire.schema.internal.parser.TypeElement;
import io.confluent.kafka.schemaregistry.protobuf.diff.Context;
import io.confluent.kafka.schemaregistry.protobuf.diff.Context.TypeElementInfo;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import io.confluent.kafka.schemaregistry.utils.JacksonMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import kotlin.Pair;
import kotlin.ranges.IntRange;

public class ProtobufSchemaUtils {

  private static final ObjectMapper jsonMapper = JacksonMapper.INSTANCE;

  public static ProtobufSchema copyOf(ProtobufSchema schema) {
    return schema.copy();
  }

  public static ProtobufSchema getSchema(Message message) {
    return message != null ? new ProtobufSchema(message.getDescriptorForType()) : null;
  }

  public static Object toObject(JsonNode value, ProtobufSchema schema) throws IOException {
    StringWriter out = new StringWriter();
    jsonMapper.writeValue(out, value);
    return toObject(out.toString(), schema);
  }

  public static Object toObject(String value, ProtobufSchema schema)
      throws InvalidProtocolBufferException {
    DynamicMessage.Builder message = schema.newMessageBuilder();
    JsonFormat.parser().merge(value, message);
    return message.build();
  }

  public static byte[] toJson(Message message) throws IOException {
    if (message == null) {
      return null;
    }
    String jsonString = JsonFormat.printer()
        .includingDefaultValueFields()
        .omittingInsignificantWhitespace()
        .print(message);
    return jsonString.getBytes(StandardCharsets.UTF_8);
  }

  protected static String toNormalizedString(ProtobufSchema schema) {
    FormatContext ctx = new FormatContext(false, true);
    return toFormattedString(ctx, schema);
  }

  protected static String toFormattedString(FormatContext ctx, ProtobufSchema schema) {
    if (ctx.normalize()) {
      ctx.collectTypeInfo(schema, true);
    }
    return toString(ctx, schema.rawSchema());
  }

  protected static String toString(ProtoFileElement protoFile) {
    FormatContext ctx = new FormatContext(false, false);
    return toString(ctx, protoFile);
  }

  private static String toString(FormatContext ctx, ProtoFileElement protoFile) {
    StringBuilder sb = new StringBuilder();
    if (protoFile.getSyntax() != null) {
      sb.append("syntax = \"");
      sb.append(protoFile.getSyntax());
      sb.append("\";\n");
    }
    if (protoFile.getPackageName() != null) {
      sb.append("package ");
      sb.append(protoFile.getPackageName());
      sb.append(";\n");
    }
    if (!protoFile.getImports().isEmpty() || !protoFile.getPublicImports().isEmpty()) {
      sb.append('\n');
      List<String> imports = protoFile.getImports();
      if (ctx.normalize()) {
        imports = imports.stream().sorted().distinct().collect(Collectors.toList());
      }
      for (String file : imports) {
        sb.append("import \"");
        sb.append(file);
        sb.append("\";\n");
      }
      List<String> publicImports = protoFile.getPublicImports();
      if (ctx.normalize()) {
        publicImports = publicImports.stream().sorted().distinct().collect(Collectors.toList());
      }
      for (String file : publicImports) {
        sb.append("import public \"");
        sb.append(file);
        sb.append("\";\n");
      }
    }
    if (!protoFile.getOptions().isEmpty()) {
      sb.append('\n');
      List<OptionElement> options = ctx.filterOptions(protoFile.getOptions());
      for (OptionElement option : options) {
        sb.append(toOptionString(ctx, option));
      }
    }
    List<TypeElement> types = filterTypes(ctx, protoFile.getTypes());
    if (!types.isEmpty()) {
      sb.append('\n');
      // Order of message types is significant since the client is using
      // the non-normalized schema to serialize message indexes
      for (TypeElement typeElement : types) {
        if (typeElement instanceof MessageElement) {
          try (Context.NamedScope nameScope = ctx.enterName(typeElement.getName())) {
            sb.append(toString(ctx, (MessageElement) typeElement));
          }
        }
      }
      for (TypeElement typeElement : types) {
        if (typeElement instanceof EnumElement) {
          try (Context.NamedScope nameScope = ctx.enterName(typeElement.getName())) {
            sb.append(toString(ctx, (EnumElement) typeElement));
          }
        }
      }
    }
    if (!ctx.ignoreExtensions() && !protoFile.getExtendDeclarations().isEmpty()) {
      sb.append('\n');
      List<ExtendElement> extendElems = protoFile.getExtendDeclarations();
      if (ctx.normalize()) {
        extendElems = extendElems.stream()
            .flatMap(e -> e.getFields().stream().map(f -> new Pair<>(resolve(ctx, e.getName()), f)))
            .collect(Collectors.groupingBy(
                Pair::getFirst,
                LinkedHashMap::new,  // deterministic order
                Collectors.mapping(Pair::getSecond, Collectors.toList()))
            )
            .entrySet()
            .stream()
            .map(e -> new ExtendElement(DEFAULT_LOCATION, e.getKey(), "", e.getValue()))
            .collect(Collectors.toList());
      }
      for (ExtendElement extendElem : extendElems) {
        sb.append(toString(ctx, extendElem));
      }
    }
    if (!protoFile.getServices().isEmpty()) {
      sb.append('\n');
      // Don't sort service elements to be consistent with the fact that
      // we don't sort message/enum elements
      for (ServiceElement service : protoFile.getServices()) {
        sb.append(toString(ctx, service));
      }
    }
    return sb.toString();
  }

  private static String toString(FormatContext ctx, ServiceElement service) {
    StringBuilder sb = new StringBuilder();
    sb.append("service ");
    sb.append(service.getName());
    sb.append(" {");
    if (!service.getOptions().isEmpty()) {
      sb.append('\n');
      List<OptionElement> options = ctx.filterOptions(service.getOptions());
      for (OptionElement option : options) {
        appendIndented(sb, toOptionString(ctx, option));
      }
    }
    if (!service.getRpcs().isEmpty()) {
      sb.append('\n');
      // Don't sort rpc elements to be consistent with the fact that
      // we don't sort message/enum elements
      for (RpcElement rpc : service.getRpcs()) {
        appendIndented(sb, toString(ctx, rpc));
      }
    }
    sb.append("}\n");
    return sb.toString();
  }

  private static String toString(FormatContext ctx, RpcElement rpc) {
    StringBuilder sb = new StringBuilder();
    sb.append("rpc ");
    sb.append(rpc.getName());
    sb.append(" (");

    if (rpc.getRequestStreaming()) {
      sb.append("stream ");
    }
    String requestType = rpc.getRequestType();
    if (ctx.normalize()) {
      requestType = resolve(ctx, requestType);
    }
    sb.append(requestType);
    sb.append(") returns (");

    if (rpc.getResponseStreaming()) {
      sb.append("stream ");
    }
    String responseType = rpc.getResponseType();
    if (ctx.normalize()) {
      responseType = resolve(ctx, responseType);
    }
    sb.append(responseType);
    sb.append(")");

    if (!rpc.getOptions().isEmpty()) {
      sb.append(" {\n");
      List<OptionElement> options = ctx.filterOptions(rpc.getOptions());
      for (OptionElement option : options) {
        appendIndented(sb, toOptionString(ctx, option));
      }
      sb.append('}');
    }

    sb.append(";\n");
    return sb.toString();
  }

  private static String toString(FormatContext ctx, EnumElement type) {
    StringBuilder sb = new StringBuilder();
    sb.append("enum ");
    sb.append(type.getName());
    sb.append(" {");

    if (!type.getReserveds().isEmpty()) {
      sb.append('\n');
      List<ReservedElement> reserveds = type.getReserveds();
      if (ctx.normalize()) {
        reserveds = reserveds.stream()
            .flatMap(r -> r.getValues().stream()
                .map(o -> new ReservedElement(
                    r.getLocation(),
                    r.getDocumentation(),
                    Collections.singletonList(o))
                )
            )
            .collect(Collectors.toList());
        Comparator<Object> cmp = Comparator.comparing(r -> {
          Object o = ((ReservedElement)r).getValues().get(0);
          if (o instanceof IntRange) {
            return ((IntRange) o).getStart();
          } else if (o instanceof Integer) {
            return (Integer) o;
          } else {
            return Integer.MAX_VALUE;
          }
        }).thenComparing(r -> ((ReservedElement) r).getValues().get(0).toString());
        reserveds.sort(cmp);
      }
      for (ReservedElement reserved : reserveds) {
        appendIndented(sb, toString(ctx, reserved));
      }
    }

    if (type.getReserveds().isEmpty()
        && (!type.getOptions().isEmpty() || !type.getConstants().isEmpty())) {
      sb.append('\n');
    }

    if (!type.getOptions().isEmpty()) {
      List<OptionElement> options = ctx.filterOptions(type.getOptions());
      for (OptionElement option : options) {
        appendIndented(sb, toOptionString(ctx, option));
      }
    }
    if (!type.getConstants().isEmpty()) {
      List<EnumConstantElement> constants = type.getConstants();
      if (ctx.normalize()) {
        constants = new ArrayList<>(constants);
        constants.sort(Comparator
            .comparing(EnumConstantElement::getTag)
            .thenComparing(EnumConstantElement::getName));
      }
      for (EnumConstantElement constant : constants) {
        appendIndented(sb, toString(ctx, constant));
      }
    }
    sb.append("}\n");
    return sb.toString();
  }

  private static String toString(FormatContext ctx, EnumConstantElement type) {
    StringBuilder sb = new StringBuilder();
    sb.append(type.getName());
    sb.append(" = ");
    sb.append(type.getTag());

    List<OptionElement> options = ctx.filterOptions(type.getOptions());
    if (!options.isEmpty()) {
      sb.append(" ");
      appendOptions(ctx, sb, options);
    }
    sb.append(";\n");
    return sb.toString();
  }

  private static String toString(FormatContext ctx, MessageElement type) {
    StringBuilder sb = new StringBuilder();
    sb.append("message ");
    sb.append(type.getName());
    sb.append(" {");

    if (!type.getReserveds().isEmpty()) {
      sb.append('\n');
      List<ReservedElement> reserveds = type.getReserveds();
      if (ctx.normalize()) {
        reserveds = reserveds.stream()
            .flatMap(r -> r.getValues().stream()
                .map(o -> new ReservedElement(
                    r.getLocation(),
                    r.getDocumentation(),
                    Collections.singletonList(o))
                )
            )
            .collect(Collectors.toList());
        Comparator<Object> cmp = Comparator.comparing(r -> {
          Object o = ((ReservedElement)r).getValues().get(0);
          if (o instanceof IntRange) {
            return ((IntRange) o).getStart();
          } else if (o instanceof Integer) {
            return (Integer) o;
          } else {
            return Integer.MAX_VALUE;
          }
        }).thenComparing(r -> ((ReservedElement) r).getValues().get(0).toString());
        reserveds.sort(cmp);
      }
      for (ReservedElement reserved : reserveds) {
        appendIndented(sb, toString(ctx, reserved));
      }
    }
    if (!type.getOptions().isEmpty()) {
      sb.append('\n');
      List<OptionElement> options = ctx.filterOptions(type.getOptions());
      for (OptionElement option : options) {
        appendIndented(sb, toOptionString(ctx, option));
      }
    }
    if (!type.getFields().isEmpty()) {
      sb.append('\n');
      List<FieldElement> fields = type.getFields();
      if (ctx.normalize()) {
        fields = new ArrayList<>(fields);
        fields.sort(Comparator.comparing(FieldElement::getTag));
      }
      for (FieldElement field : fields) {
        appendIndented(sb, toString(ctx, field));
      }
    }
    if (!type.getOneOfs().isEmpty()) {
      sb.append('\n');
      List<OneOfElement> oneOfs = type.getOneOfs();
      if (ctx.normalize()) {
        oneOfs = oneOfs.stream()
            .filter(o -> !o.getFields().isEmpty())
            .map(o -> {
              List<FieldElement> fields = new ArrayList<>(o.getFields());
              fields.sort(Comparator.comparing(FieldElement::getTag));
              return new OneOfElement(o.getName(), o.getDocumentation(),
                  fields, o.getGroups(), o.getOptions());
            })
            .collect(Collectors.toList());
        oneOfs.sort(Comparator.comparing(o -> o.getFields().get(0).getTag()));
      }
      for (OneOfElement oneOf : oneOfs) {
        appendIndented(sb, toString(ctx, oneOf));
      }
    }
    if (!type.getGroups().isEmpty()) {
      sb.append('\n');
      List<GroupElement> groups = type.getGroups();
      if (ctx.normalize()) {
        groups = new ArrayList<>(groups);
        groups.sort(Comparator.comparing(GroupElement::getTag));
      }
      for (GroupElement group : groups) {
        appendIndented(sb, toString(ctx, group));
      }
    }
    if (!ctx.ignoreExtensions() && !type.getExtensions().isEmpty()) {
      sb.append('\n');
      List<ExtensionsElement> extensions = type.getExtensions();
      if (ctx.normalize()) {
        extensions = extensions.stream()
            .flatMap(r -> r.getValues().stream()
                .map(o -> new ExtensionsElement(
                    r.getLocation(),
                    r.getDocumentation(),
                    Collections.singletonList(o))
                )
            )
            .collect(Collectors.toList());
        Comparator<Object> cmp = Comparator.comparing(r -> {
          Object o = ((ExtensionsElement)r).getValues().get(0);
          if (o instanceof IntRange) {
            return ((IntRange) o).getStart();
          } else if (o instanceof Integer) {
            return (Integer) o;
          } else {
            return Integer.MAX_VALUE;
          }
        });
        extensions.sort(cmp);
      }
      for (ExtensionsElement extension : extensions) {
        appendIndented(sb, toString(ctx, extension));
      }
    }
    List<TypeElement> types = filterTypes(ctx, type.getNestedTypes());
    if (!types.isEmpty()) {
      sb.append('\n');
      // Order of message types is significant since the client is using
      // the non-normalized schema to serialize message indexes
      for (TypeElement typeElement : types) {
        if (typeElement instanceof MessageElement) {
          try (Context.NamedScope nameScope = ctx.enterName(typeElement.getName())) {
            appendIndented(sb, toString(ctx, (MessageElement) typeElement));
          }
        }
      }
      for (TypeElement typeElement : types) {
        if (typeElement instanceof EnumElement) {
          try (Context.NamedScope nameScope = ctx.enterName(typeElement.getName())) {
            appendIndented(sb, toString(ctx, (EnumElement) typeElement));
          }
        }
      }
    }
    sb.append("}\n");
    return sb.toString();
  }

  private static String toString(FormatContext ctx, ReservedElement type) {
    StringBuilder sb = new StringBuilder();
    sb.append("reserved ");

    boolean first = true;
    for (Object value : type.getValues()) {
      if (first) {
        first = false;
      } else {
        sb.append(", ");
      }
      if (value instanceof String) {
        sb.append("\"");
        sb.append(value);
        sb.append("\"");
      } else if (value instanceof Integer) {
        sb.append(value);
      } else if (value instanceof IntRange) {
        IntRange range = (IntRange) value;
        sb.append(range.getStart());
        sb.append(" to ");
        int last = range.getEndInclusive();
        if (last < MAX_TAG_VALUE) {
          sb.append(last);
        } else {
          sb.append("max");
        }
      } else {
        throw new IllegalArgumentException();
      }
    }
    sb.append(";\n");
    return sb.toString();
  }

  private static String toString(FormatContext ctx, ExtensionsElement type) {
    StringBuilder sb = new StringBuilder();
    sb.append("extensions ");

    boolean first = true;
    for (Object value : type.getValues()) {
      if (first) {
        first = false;
      } else {
        sb.append(", ");
      }
      if (value instanceof Integer) {
        sb.append(value);
      } else if (value instanceof IntRange) {
        IntRange range = (IntRange) value;
        sb.append(range.getStart());
        sb.append(" to ");
        int last = range.getEndInclusive();
        if (last < MAX_TAG_VALUE) {
          sb.append(last);
        } else {
          sb.append("max");
        }
      } else {
        throw new IllegalArgumentException();
      }
    }
    sb.append(";\n");
    return sb.toString();
  }

  private static String toString(FormatContext ctx, OneOfElement type) {
    StringBuilder sb = new StringBuilder();
    sb.append("oneof ");
    sb.append(type.getName());
    sb.append(" {");

    if (!type.getOptions().isEmpty()) {
      sb.append('\n');
      List<OptionElement> options = ctx.filterOptions(type.getOptions());
      for (OptionElement option : options) {
        appendIndented(sb, toOptionString(ctx, option));
      }
    }
    if (!type.getFields().isEmpty()) {
      sb.append('\n');
      // Fields have already been sorted while sorting oneOfs in the calling method
      List<FieldElement> fields = type.getFields();
      for (FieldElement field : fields) {
        appendIndented(sb, toString(ctx, field));
      }
    }
    if (!type.getGroups().isEmpty()) {
      sb.append('\n');
      List<GroupElement> groups = type.getGroups();
      if (ctx.normalize()) {
        groups = new ArrayList<>(groups);
        groups.sort(Comparator.comparing(GroupElement::getTag));
      }
      for (GroupElement group : groups) {
        appendIndented(sb, toString(ctx, group));
      }
    }
    sb.append("}\n");
    return sb.toString();
  }

  private static String toString(FormatContext ctx, GroupElement group) {
    StringBuilder sb = new StringBuilder();
    Label label = group.getLabel();
    if (label != null) {
      sb.append(label.name().toLowerCase(Locale.US));
      sb.append(" ");
    }
    sb.append("group ");
    sb.append(group.getName());
    sb.append(" = ");
    sb.append(group.getTag());
    sb.append(" {");
    if (!group.getFields().isEmpty()) {
      sb.append('\n');
      List<FieldElement> fields = group.getFields();
      if (ctx.normalize()) {
        fields = new ArrayList<>(fields);
        fields.sort(Comparator.comparing(FieldElement::getTag));
      }
      for (FieldElement field : fields) {
        appendIndented(sb, toString(ctx, field));
      }
    }
    sb.append("}\n");
    return sb.toString();
  }

  private static String toString(FormatContext ctx, ExtendElement extendElem) {
    StringBuilder sb = new StringBuilder();
    sb.append("extend ");
    // Names have been resolved when grouping by name
    String extendName = extendElem.getName();
    sb.append(extendName);
    sb.append(" {");
    if (!extendElem.getFields().isEmpty()) {
      sb.append('\n');
      List<FieldElement> fields = extendElem.getFields();
      if (ctx.normalize()) {
        fields = new ArrayList<>(fields);
        fields.sort(Comparator.comparing(FieldElement::getTag));
      }
      for (FieldElement field : fields) {
        appendIndented(sb, toString(ctx, field));
      }
    }
    sb.append("}\n");
    return sb.toString();
  }

  private static String toString(FormatContext ctx, FieldElement field) {
    StringBuilder sb = new StringBuilder();
    Label label = field.getLabel();
    String fieldType = field.getType();
    ProtoType fieldProtoType = ProtoType.get(fieldType);
    if (ctx.normalize()) {
      if (!fieldProtoType.isScalar() && !fieldProtoType.isMap()) {
        // See if the fieldType resolves to a message representing a map
        fieldType = resolve(ctx, fieldType);
        TypeElementInfo typeInfo = ctx.getTypeForFullName(fieldType, true);
        if (typeInfo != null && typeInfo.isMap()) {
          fieldProtoType = typeInfo.getMapType();
        } else {
          fieldProtoType = ProtoType.get(fieldType);
        }
      }
      ProtoType mapValueType = fieldProtoType.getValueType();
      if (fieldProtoType.isMap() && mapValueType != null) {
        // Ensure the value of the map is fully resolved
        String valueType = ctx.resolve(mapValueType.toString(), true);
        if (valueType != null) {
          fieldProtoType = ProtoType.get(
              // Note we add a leading dot to valueType
              "map<" + fieldProtoType.getKeyType() + ", ." + valueType + ">"
          );
        }
        label = null;  // don't emit label for map
      }
      fieldType = fieldProtoType.toString();
    }
    if (label != null) {
      sb.append(label.name().toLowerCase(Locale.US));
      sb.append(" ");
    }
    sb.append(fieldType);
    sb.append(" ");
    sb.append(field.getName());
    sb.append(" = ");
    sb.append(field.getTag());

    List<OptionElement> optionsWithSpecialValues = new ArrayList<>(field.getOptions());
    String defaultValue = field.getDefaultValue();
    if (defaultValue != null) {
      optionsWithSpecialValues.add(
          OptionElement.Companion.create("default", toKind(fieldProtoType), defaultValue));
    }
    String jsonName = field.getJsonName();
    if (jsonName != null) {
      optionsWithSpecialValues.add(
          OptionElement.Companion.create("json_name", Kind.STRING, jsonName));
    }
    optionsWithSpecialValues = ctx.filterOptions(optionsWithSpecialValues);
    if (!optionsWithSpecialValues.isEmpty()) {
      sb.append(" ");
      appendOptions(ctx, sb, optionsWithSpecialValues);
    }

    sb.append(";\n");
    return sb.toString();
  }

  @SuppressWarnings("unchecked")
  private static String toString(FormatContext ctx, OptionElement option) {
    StringBuilder sb = new StringBuilder();
    String name = option.getName();
    if (option.isParenthesized()) {
      sb.append("(").append(name).append(")");
    } else {
      sb.append(name);
    }
    Object value = option.getValue();
    switch (option.getKind()) {
      case STRING:
        sb.append(" = \"");
        sb.append(escapeChars(value.toString()));
        sb.append("\"");
        break;
      case BOOLEAN:
      case NUMBER:
      case ENUM:
        sb.append(" = ");
        sb.append(value);
        break;
      case OPTION:
        sb.append(".");
        // Treat nested options as non-parenthesized always, prevents double parentheses.
        sb.append(toString(ctx, (OptionElement) value));
        break;
      case MAP:
        sb.append(" = {\n");
        formatOptionMap(ctx, sb, (Map<String, Object>) value);
        sb.append('}');
        break;
      case LIST:
        sb.append(" = ");
        appendOptions(ctx, sb, (List<OptionElement>) value);
        break;
      default:
        break;
    }
    return sb.toString();
  }

  private static List<TypeElement> filterTypes(FormatContext ctx, List<TypeElement> types) {
    if (ctx.normalize()) {
      return types.stream()
          .filter(type -> {
            if (type instanceof MessageElement) {
              TypeElementInfo typeInfo = ctx.getType(type.getName(), true);
              // Don't emit synthetic map message
              return typeInfo == null || !typeInfo.isMap();
            } else {
              return true;
            }
          })
          .collect(Collectors.toList());
    } else {
      return types;
    }
  }
  
  private static void formatOptionMap(
      FormatContext ctx, StringBuilder sb, Map<String, Object> valueMap) {
    int lastIndex = valueMap.size() - 1;
    int index = 0;
    Collection<String> keys = valueMap.keySet();
    if (ctx.normalize()) {
      keys = keys.stream().sorted().collect(Collectors.toList());
    }
    for (String key : keys) {
      String endl = index != lastIndex ? "," : "";
      String kv = new StringBuilder()
          .append(key)
          .append(": ")
          .append(formatOptionMapValue(ctx, valueMap.get(key)))
          .append(endl)
          .toString();
      appendIndented(sb, kv);
      index++;
    }
  }

  @SuppressWarnings("unchecked")
  private static String formatOptionMapValue(FormatContext ctx, Object value) {
    StringBuilder sb = new StringBuilder();
    if (value instanceof  String) {
      sb.append("\"");
      sb.append(escapeChars(value.toString()));
      sb.append("\"");
    } else if (value instanceof Map) {
      sb.append("{\n");
      formatOptionMap(ctx, sb, (Map<String, Object>) value);
      sb.append('}');
    } else if (value instanceof List) {
      List<Object> list = (List<Object>) value;
      sb.append("[\n");
      int lastIndex = list.size() - 1;
      for (int i = 0; i < list.size(); i++) {
        String endl = i != lastIndex ? "," : "";
        String v = new StringBuilder()
            .append(formatOptionMapValue(ctx, list.get(i)))
            .append(endl)
            .toString();
        appendIndented(sb, v);
      }
      sb.append("]");
    } else if (value instanceof OptionElement.OptionPrimitive) {
      OptionElement.OptionPrimitive primitive = (OptionElement.OptionPrimitive)value;
      switch (primitive.getKind()) {
        case BOOLEAN:
        case ENUM:
        case NUMBER:
          sb.append(primitive.getValue());
          break;
        default:
          sb.append(formatOptionMapValue(ctx, primitive.getValue()));
      }
    } else {
      sb.append(value);
    }
    return sb.toString();
  }

  private static String toOptionString(FormatContext ctx, OptionElement option) {
    StringBuilder sb = new StringBuilder();
    sb.append("option ")
        .append(toString(ctx, option))
        .append(";\n");
    return sb.toString();
  }

  private static void appendOptions(
      FormatContext ctx, StringBuilder sb, List<OptionElement> options) {
    int count = options.size();
    if (count == 1) {
      sb.append('[')
          .append(toString(ctx, options.get(0)))
          .append(']');
      return;
    }
    sb.append("[\n");
    for (int i = 0; i < count; i++) {
      String endl = i < count - 1 ? "," : "";
      appendIndented(sb, toString(ctx, options.get(i)) + endl);
    }
    sb.append(']');
  }

  private static Kind toKind(ProtoType protoType) {
    switch (protoType.getSimpleName()) {
      case "bool":
        return OptionElement.Kind.BOOLEAN;
      case "string":
        return OptionElement.Kind.STRING;
      case "bytes":
      case "double":
      case "float":
      case "fixed32":
      case "fixed64":
      case "int32":
      case "int64":
      case "sfixed32":
      case "sfixed64":
      case "sint32":
      case "sint64":
      case "uint32":
      case "uint64":
        return OptionElement.Kind.NUMBER;
      default:
        return OptionElement.Kind.ENUM;
    }
  }

  private static void appendIndented(StringBuilder sb, String value) {
    List<String> lines = Arrays.asList(value.split("\n"));
    if (lines.size() > 1 && lines.get(lines.size() - 1).isEmpty()) {
      lines.remove(lines.size() - 1);
    }
    for (String line : lines) {
      sb.append("  ")
              .append(line)
              .append('\n');
    }
  }

  public static String escapeChars(String input) {
    StringBuilder buffer = new StringBuilder(input.length());
    for (int i = 0; i < input.length(); i++) {
      char curr = input.charAt(i);
      switch (curr) {
        case Ascii.BEL:
          buffer.append("\\a");
          break;
        case Ascii.BS:
          buffer.append("\\b");
          break;
        case Ascii.FF:
          buffer.append("\\f");
          break;
        case Ascii.NL:
          buffer.append("\\n");
          break;
        case Ascii.CR:
          buffer.append("\\r");
          break;
        case Ascii.HT:
          buffer.append("\\t");
          break;
        case Ascii.VT:
          buffer.append("\\v");
          break;
        case '\\':
          buffer.append("\\\\");
          break;
        case '\'':
          buffer.append("\\'");
          break;
        case '\"':
          buffer.append("\\\"");
          break;
        default:
          buffer.append(curr);
      }
    }
    return buffer.toString();
  }

  private static String resolve(Context ctx, String type) {
    String resolved = ctx.resolve(type, true);
    if (resolved == null) {
      throw new IllegalArgumentException("Could not resolve type: " + type);
    }
    return "." + resolved;
  }

  static class FormatContext extends Context {
    private boolean ignoreExtensions;
    private boolean normalize;

    public FormatContext(boolean ignoreExtensions, boolean normalize) {
      super();
      this.ignoreExtensions = ignoreExtensions;
      this.normalize = normalize;
    }

    public boolean ignoreExtensions() {
      return ignoreExtensions;
    }

    public boolean normalize() {
      return normalize;
    }

    public List<OptionElement> filterOptions(List<OptionElement> options) {
      if (options.isEmpty()) {
        return options;
      }
      if (ignoreExtensions) {
        // Remove custom options
        options = options.stream()
            .filter(o -> !o.isParenthesized() || o.getName().startsWith(CONFLUENT_PREFIX))
            .collect(Collectors.toList());
      }
      if (normalize) {
        options = new ArrayList<>(options);
        options.sort(Comparator.comparing(OptionElement::getName));
      }
      return options;
    }
  }
}