/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.mapper;

import org.elasticsearch.Version;
import org.elasticsearch.common.logging.DeprecationCategory;
import org.elasticsearch.common.logging.DeprecationLogger;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.ToXContentFragment;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.mapper.FieldMapper.Parameter;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Definition of a runtime field that can be defined as part of the runtime section of the index mappings
 */
public interface RuntimeField extends ToXContentFragment {

    @Override
    default XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(name());
        builder.field("type", typeName());
        doXContentBody(builder, params);
        builder.endObject();
        return builder;
    }

    /**
     * Prints out the parameters that subclasses expose
     */
    void doXContentBody(XContentBuilder builder, Params params) throws IOException;

    /**
     * Exposes the name of the runtime field
     * @return name of the field
     */
    String name();

    /**
     * Exposes the type of the runtime field
     * @return type of the field
     */
    String typeName();

    /**
     * Exposes the {@link MappedFieldType}s backing this runtime field, used to execute queries, run aggs etc.
     * @return the {@link MappedFieldType}s backing this runtime field
     */
    Collection<MappedFieldType> asMappedFieldTypes();

    abstract class Builder implements ToXContent {
        final String name;
        final Parameter<Map<String, String>> meta = Parameter.metaParam();

        private static final DeprecationLogger deprecationLogger = DeprecationLogger.getLogger(RuntimeField.class);

        protected Builder(String name) {
            this.name = name;
        }

        public Map<String, String> meta() {
            return meta.getValue();
        }

        protected List<Parameter<?>> getParameters() {
            return Collections.singletonList(meta);
        }

        protected abstract RuntimeField createRuntimeField(Mapper.TypeParser.ParserContext parserContext);

        @Override
        public final XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            boolean includeDefaults = params.paramAsBoolean("include_defaults", false);
            for (Parameter<?> parameter : getParameters()) {
                parameter.toXContent(builder, includeDefaults);
            }
            return builder;
        }

        public final void parse(String name, Mapper.TypeParser.ParserContext parserContext, Map<String, Object> fieldNode) {
            Map<String, Parameter<?>> paramsMap = new HashMap<>();
            for (Parameter<?> param : getParameters()) {
                paramsMap.put(param.name, param);
            }
            String type = (String) fieldNode.remove("type");
            for (Iterator<Map.Entry<String, Object>> iterator = fieldNode.entrySet().iterator(); iterator.hasNext();) {
                Map.Entry<String, Object> entry = iterator.next();
                final String propName = entry.getKey();
                final Object propNode = entry.getValue();
                Parameter<?> parameter = paramsMap.get(propName);
                if (parameter == null) {
                    if (parserContext.isFromDynamicTemplate() && parserContext.indexVersionCreated().before(Version.V_8_0_0)) {
                        // The parameter is unknown, but this mapping is from a dynamic template.
                        // Until 7.x it was possible to use unknown parameters there, so for bwc we need to ignore it
                        deprecationLogger.deprecate(DeprecationCategory.API, propName,
                            "Parameter [{}] is used in a dynamic template mapping and has no effect on type [{}]. "
                                + "Usage will result in an error in future major versions and should be removed.",
                            propName,
                            type
                        );
                        iterator.remove();
                        continue;
                    }
                    throw new MapperParsingException(
                        "unknown parameter [" + propName + "] on runtime field [" + name + "] of type [" + type + "]"
                    );
                }
                if (propNode == null && parameter.canAcceptNull() == false) {
                    throw new MapperParsingException("[" + propName + "] on runtime field [" + name
                        + "] of type [" + type + "] must not have a [null] value");
                }
                parameter.parse(name, parserContext, propNode);
                iterator.remove();
            }
        }
    }

    /**
     * Parser for a runtime field. Creates the appropriate {@link RuntimeField} for a runtime field,
     * as defined in the runtime section of the index mappings.
     */
    final class Parser {
        private final Function<String, Builder> builderFunction;

        public Parser(Function<String, RuntimeField.Builder> builderFunction) {
            this.builderFunction = builderFunction;
        }

        RuntimeField parse(String name, Map<String, Object> node, Mapper.TypeParser.ParserContext parserContext)
            throws MapperParsingException {

            RuntimeField.Builder builder = builderFunction.apply(name);
            builder.parse(name, parserContext, node);
            return builder.createRuntimeField(parserContext);
        }
    }

    /**
     * Parse runtime fields from the provided map, using the provided parser context.
     * @param node the map that holds the runtime fields configuration
     * @param parserContext the parser context that holds info needed when parsing mappings
     * @param supportsRemoval whether a null value for a runtime field should be properly parsed and
     *                        translated to the removal of such runtime field
     * @return the parsed runtime fields
     */
    static Map<String, RuntimeField> parseRuntimeFields(Map<String, Object> node,
                                                        Mapper.TypeParser.ParserContext parserContext,
                                                        boolean supportsRemoval) {
        Map<String, RuntimeField> runtimeFields = new HashMap<>();
        Iterator<Map.Entry<String, Object>> iterator = node.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Object> entry = iterator.next();
            String fieldName = entry.getKey();
            if (entry.getValue() == null) {
                if (supportsRemoval) {
                    runtimeFields.put(fieldName, null);
                } else {
                    throw new MapperParsingException("Runtime field [" + fieldName + "] was set to null but its removal is not supported " +
                        "in this context");
                }
            } else if (entry.getValue() instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> propNode = new HashMap<>(((Map<String, Object>) entry.getValue()));
                Object typeNode = propNode.get("type");
                String type;
                if (typeNode == null) {
                    throw new MapperParsingException("No type specified for runtime field [" + fieldName + "]");
                } else {
                    type = typeNode.toString();
                }
                Parser typeParser = parserContext.runtimeFieldParser(type);
                if (typeParser == null) {
                    throw new MapperParsingException("No handler for type [" + type +
                        "] declared on runtime field [" + fieldName + "]");
                }
                runtimeFields.put(fieldName, typeParser.parse(fieldName, propNode, parserContext));
                propNode.remove("type");
                MappingParser.checkNoRemainingFields(fieldName, propNode);
                iterator.remove();
            } else {
                throw new MapperParsingException("Expected map for runtime field [" + fieldName + "] definition but got a "
                    + entry.getValue().getClass().getName());
            }
        }
        return Collections.unmodifiableMap(runtimeFields);
    }

    /**
     * Collect and return all {@link MappedFieldType} exposed by the provided {@link RuntimeField}s.
     * Note that validation is performed to make sure that there are no name clashes among the collected runtime fields.
     * This is because runtime fields with the same name are not accepted as part of the same section.
     * @param runtimeFields the runtime to extract the mapped field types from
     * @return the collected mapped field types
     */
    static Map<String, MappedFieldType> collectFieldTypes(Collection<RuntimeField> runtimeFields) {
        return runtimeFields.stream()
            .flatMap(runtimeField -> {
                List<String> names = runtimeField.asMappedFieldTypes().stream().map(MappedFieldType::name)
                    .filter(name -> name.equals(runtimeField.name()) == false
                        && (name.startsWith(runtimeField.name() + ".") == false
                        || name.length() > runtimeField.name().length() + 1 == false))
                    .collect(Collectors.toList());
                if (names.isEmpty() == false) {
                    throw new IllegalStateException("Found sub-fields with name not belonging to the parent field they are part of "
                        + names);
                }
                return runtimeField.asMappedFieldTypes().stream();
            })
            .collect(Collectors.toUnmodifiableMap(MappedFieldType::name, mappedFieldType -> mappedFieldType,
                (t, t2) -> {
                    throw new IllegalArgumentException("Found two runtime fields with same name [" + t.name() + "]");
                }));
    }
}
