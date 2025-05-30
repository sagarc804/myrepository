/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jkiss.dbeaver.utils;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.utils.CommonUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Preferences utilities
 */
public class PrefUtils {

    private static final Log log = Log.getLog(PrefUtils.class);

    public static void savePreferenceStore(@NotNull DBPPreferenceStore store) {
        try {
            store.save();
        } catch (IOException e) {
            log.warn(e);
        }
    }

    public static void setDefaultPreferenceValue(@NotNull DBPPreferenceStore store, @NotNull String name, @NotNull Object value) {
        if (CommonUtils.isEmpty(store.getDefaultString(name))) {
            store.setDefault(name, value.toString());
        }
    }

    @Nullable
    public static Object getPreferenceValue(@NotNull DBPPreferenceStore store, @NotNull String propName, @Nullable Class<?> valueType) {
        try {
            final String str = store.getString(propName);
            if (str == null) {
                return null;
            }
            if (valueType == null || CharSequence.class.isAssignableFrom(valueType)) {
                return CommonUtils.isEmpty(str) ? null : str;
            } else if (valueType == Boolean.class || valueType == Boolean.TYPE) {
                return CommonUtils.toBoolean(str);
            } else if (valueType == Long.class || valueType == Long.TYPE) {
                return CommonUtils.toLong(str);
            } else if (valueType == Integer.class || valueType == Integer.TYPE ||
                valueType == Short.class || valueType == Short.TYPE ||
                valueType == Byte.class || valueType == Byte.TYPE) {
                return CommonUtils.toInt(str);
            } else if (valueType == Double.class || valueType == Double.TYPE) {
                return CommonUtils.toDouble(str);
            } else if (valueType == Float.class || valueType == Float.TYPE) {
                return CommonUtils.toFloat(store);
            } else if (valueType == BigInteger.class) {
                return new BigInteger(str);
            } else if (valueType == BigDecimal.class) {
                return new BigDecimal(str);
            }
        } catch (RuntimeException e) {
            log.error(e);
        }
        final String string = store.getString(propName);
        return CommonUtils.isEmpty(string) ? null : string;
    }

    public static void setPreferenceValue(@NotNull DBPPreferenceStore store, @NotNull String propName, @Nullable Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof CharSequence) {
            store.setValue(propName, value.toString());
        } else if (value instanceof Boolean b) {
            store.setValue(propName, b);
        } else if (value instanceof Long l) {
            store.setValue(propName, l);
        } else if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            store.setValue(propName, ((Number) value).intValue());
        } else if (value instanceof Double d) {
            store.setValue(propName, d);
        } else if (value instanceof Float f) {
            store.setValue(propName, f);
        } else {
            store.setValue(propName, value.toString());
        }
    }

    public static void setPreferenceDefaultValue(@NotNull DBPPreferenceStore store, @NotNull String propName, @Nullable Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof CharSequence) {
            store.setDefault(propName, value.toString());
        } else if (value instanceof Boolean b) {
            store.setDefault(propName, b);
        } else if (value instanceof Long l) {
            store.setDefault(propName, l);
        } else if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            store.setDefault(propName, ((Number) value).intValue());
        } else if (value instanceof Double d) {
            store.setDefault(propName, d);
        } else if (value instanceof Float f) {
            store.setDefault(propName, f);
        } else {
            store.setDefault(propName, value.toString());
        }
    }

}
