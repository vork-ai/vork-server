package sh.vork.typegen;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Converts a flat multipart/form-data parameter map into an instance of any
 * Java record class using reflection.
 *
 * <h3>Naming convention</h3>
 * Top-level fields map directly: a record {@code name} field expects a
 * parameter named {@code name}.
 *
 * <p>Nested records use dot notation: {@code address.street}, {@code address.city}.
 *
 * <p>Lists use indexed bracket notation: {@code tags[0]}, {@code tags[1]}, or
 * plain repeated keys {@code tags} (multiple values).
 *
 * <p>Nested records inside lists: {@code items[0].name}, {@code items[0].price}.
 *
 * <h3>Type coercion</h3>
 * Scalar field types are coerced via Jackson's {@link ObjectMapper}: the string
 * value is quoted and parsed, so {@code int}, {@code long}, {@code double},
 * {@code boolean}, {@code BigDecimal}, {@code String}, etc. all work without
 * special-casing.
 *
 * <p>If a parameter for a required field is absent the field receives
 * {@code null} (or {@code 0}/{@code false} for primitives).
 */
@Component
public class FormToObjectConverter {

    private final ObjectMapper objectMapper;

    public FormToObjectConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Converts {@code params} into an instance of {@code targetClass}.
     *
     * @param params      servlet request parameter map (parameter name → values array)
     * @param targetClass the record class to instantiate
     * @return a fully constructed instance of {@code targetClass}
     * @throws FormConversionException if the conversion fails
     */
    public Object convert(Map<String, String[]> params, Class<?> targetClass) {
        try {
            return convertRecord(params, "", targetClass);
        } catch (FormConversionException e) {
            throw e;
        } catch (Exception e) {
            throw new FormConversionException("Failed to convert form to " + targetClass.getName(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Core recursive converter
    // -------------------------------------------------------------------------

    private Object convertRecord(Map<String, String[]> params, String prefix, Class<?> clazz) throws Exception {
        if (!clazz.isRecord()) {
            throw new FormConversionException(clazz.getName() + " is not a record");
        }

        RecordComponent[] components = clazz.getRecordComponents();
        Object[] args = new Object[components.length];
        Class<?>[] paramTypes = new Class[components.length];

        for (int i = 0; i < components.length; i++) {
            RecordComponent comp = components[i];
            paramTypes[i] = comp.getType();
            String key = prefix.isEmpty() ? comp.getName() : prefix + "." + comp.getName();

            args[i] = resolveValue(params, key, comp.getType(), comp.getGenericType());
        }

        return clazz.getDeclaredConstructor(paramTypes).newInstance(args);
    }

    private Object resolveValue(Map<String, String[]> params, String key,
                                Class<?> type, java.lang.reflect.Type genericType) throws Exception {
        if (type.isRecord()) {
            return convertRecord(params, key, type);
        }

        if (type == List.class || type == java.util.Collection.class) {
            return convertList(params, key, genericType);
        }

        // Scalar — find the first value for this key
        String[] values = params.get(key);
        if (values == null || values.length == 0) {
            return defaultValue(type);
        }
        return coerce(values[0], type);
    }

    private List<Object> convertList(Map<String, String[]> params, String key,
                                     java.lang.reflect.Type genericType) throws Exception {
        Class<?> elementType = resolveListElementType(genericType);
        List<Object> result = new ArrayList<>();

        if (elementType != null && elementType.isRecord()) {
            // Indexed nested records: key[0].field, key[1].field, ...
            for (int i = 0; ; i++) {
                String indexedPrefix = key + "[" + i + "]";
                if (!hasAnyKeyWithPrefix(params, indexedPrefix)) break;
                result.add(convertRecord(params, indexedPrefix, elementType));
            }
        } else {
            // Indexed scalars: key[0], key[1], ... OR repeated key
            boolean indexed = false;
            for (int i = 0; ; i++) {
                String indexedKey = key + "[" + i + "]";
                String[] vals = params.get(indexedKey);
                if (vals == null) {
                    if (i == 0) break; // try plain repeated keys instead
                    break;
                }
                indexed = true;
                for (String v : vals) {
                    result.add(elementType != null ? coerce(v, elementType) : v);
                }
            }
            if (!indexed) {
                // Fall back to plain repeated params: key=a&key=b
                String[] vals = params.get(key);
                if (vals != null) {
                    for (String v : vals) {
                        result.add(elementType != null ? coerce(v, elementType) : v);
                    }
                }
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Object coerce(String value, Class<?> type) throws Exception {
        if (type == String.class) return value;
        if (type == boolean.class || type == Boolean.class) return Boolean.parseBoolean(value);
        if (type == int.class || type == Integer.class) return Integer.parseInt(value.trim());
        if (type == long.class || type == Long.class) return Long.parseLong(value.trim());
        if (type == double.class || type == Double.class) return Double.parseDouble(value.trim());
        if (type == float.class || type == Float.class) return Float.parseFloat(value.trim());
        // For BigDecimal, UUID, and any other type, delegate to Jackson
        String json = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        return objectMapper.readValue(json, type);
    }

    private Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == float.class) return 0.0f;
        if (type == List.class) return new ArrayList<>();
        return null;
    }

    private Class<?> resolveListElementType(java.lang.reflect.Type genericType) {
        if (genericType instanceof java.lang.reflect.ParameterizedType pt) {
            java.lang.reflect.Type arg = pt.getActualTypeArguments()[0];
            if (arg instanceof Class<?> c) return c;
        }
        return null;
    }

    private boolean hasAnyKeyWithPrefix(Map<String, String[]> params, String prefix) {
        for (String key : params.keySet()) {
            if (key.startsWith(prefix)) return true;
        }
        return false;
    }
}
