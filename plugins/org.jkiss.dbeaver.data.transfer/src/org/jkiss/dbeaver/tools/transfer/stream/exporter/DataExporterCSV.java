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
package org.jkiss.dbeaver.tools.transfer.stream.exporter;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.*;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.data.DBDContent;
import org.jkiss.dbeaver.model.data.DBDContentStorage;
import org.jkiss.dbeaver.model.data.DBDDisplayFormat;
import org.jkiss.dbeaver.model.exec.DBCResultSet;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.DBExecUtils;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.tools.transfer.DTUtils;
import org.jkiss.dbeaver.tools.transfer.stream.IAppendableDataExporter;
import org.jkiss.dbeaver.tools.transfer.stream.IStreamDataExporterSite;
import org.jkiss.dbeaver.tools.transfer.stream.StreamTransferUtils;
import org.jkiss.dbeaver.utils.ContentUtils;
import org.jkiss.dbeaver.utils.GeneralUtils;
import org.jkiss.utils.CommonUtils;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * CSV Exporter
 */
public class DataExporterCSV extends StreamExporterAbstract implements IAppendableDataExporter {

    private static final String PROP_DELIMITER = "delimiter";
    private static final String PROP_ROW_DELIMITER = "rowDelimiter";
    private static final String PROP_HEADER = "header";
    private static final String PROP_HEADER_FORMAT = "headerFormat";
    private static final String PROP_HEADER_CASE = "headerCase";
    private static final String PROP_QUOTE_CHAR = "quoteChar";
    private static final String PROP_QUOTE_ALWAYS = "quoteAlways";
    private static final String PROP_QUOTE_NEVER = "quoteNever";
    private static final String PROP_NULL_STRING = "nullString";
    private static final String PROP_FORMAT_NUMBERS = "formatNumbers";
    private static final String PROP_LINE_FEED_ESCAPE_STRING = "lineFeedEscapeString";
    private static final String PROP_FORMAT_ARRAY = "formatArray";
    private static final Pattern LINE_BREAK_REGEX = Pattern.compile("\\r\\n|\\n");

    private static final String DEF_QUOTE_CHAR = "\"";
    private static final String DEFAULT_ARRAY_BRACKETS = "{ }";
    private boolean formatNumbers;

    enum HeaderPosition {
        none,
        top,
        bottom,
        both
    }

    enum HeaderFormat {
        label,
        description,
        both
    }

    private static final String ROW_DELIMITER_DEFAULT = "default";

    private String delimiter;
    private char quoteChar = '"';
    private boolean useQuotes = true;
    private QuoteStrategy quoteStrategy = QuoteStrategy.DISABLED;
    private String rowDelimiter;
    private String nullString;
    private HeaderPosition headerPosition;
    private HeaderFormat headerFormat;
    private DBPIdentifierCase headerCase;
    private String lineFeedEscapeString;
    private DBDAttributeBinding[] columns;
    private DataExporterArrayFormat dataExporterArrayFormat;

    private final StringBuilder buffer = new StringBuilder();

    @Override
    public void init(IStreamDataExporterSite site) throws DBException
    {
        super.init(site);
        Map<String, Object> properties = site.getProperties();
        this.delimiter = StreamTransferUtils.getDelimiterString(properties, PROP_DELIMITER);
        this.rowDelimiter = StreamTransferUtils.getDelimiterString(properties, PROP_ROW_DELIMITER);
        if (ROW_DELIMITER_DEFAULT.equalsIgnoreCase(this.rowDelimiter.trim())) {
            this.rowDelimiter = GeneralUtils.getDefaultLineSeparator();
        }
        this.lineFeedEscapeString = CommonUtils.toString(properties.get(PROP_LINE_FEED_ESCAPE_STRING), "")
            .replace("\\t", "\t")
            .replace("\\n", "\n")
            .replace("\\r", "\r");
        Object quoteProp = properties.get(PROP_QUOTE_CHAR);
        String quoteStr = quoteProp == null ? DEF_QUOTE_CHAR : quoteProp.toString();
        if (!CommonUtils.isEmpty(quoteStr)) {
            quoteChar = quoteStr.charAt(0);
        }
        if (CommonUtils.toBoolean(properties.get(PROP_QUOTE_NEVER))) {
            quoteChar = ' ';
        }

        Object nullStringProp = properties.get(PROP_NULL_STRING);
        nullString = nullStringProp == null ? null : nullStringProp.toString();
        useQuotes = quoteChar != ' ';
        quoteStrategy = QuoteStrategy.fromValue(CommonUtils.toString(properties.get(PROP_QUOTE_ALWAYS)));

        if (headerPosition == null) {
            headerPosition = CommonUtils.valueOf(HeaderPosition.class, CommonUtils.toString(properties.get(PROP_HEADER)), HeaderPosition.top);
        }

        headerFormat = CommonUtils.valueOf(HeaderFormat.class, CommonUtils.toString(properties.get(PROP_HEADER_FORMAT)), HeaderFormat.label);
        formatNumbers = CommonUtils.toBoolean(getSite().getProperties().get(PROP_FORMAT_NUMBERS));
        headerCase = switch (CommonUtils.toString(properties.get(PROP_HEADER_CASE))) {
            case "as is" -> DBPIdentifierCase.MIXED;
            case "lower" -> DBPIdentifierCase.LOWER;
            default -> DBPIdentifierCase.UPPER;
        };
        dataExporterArrayFormat = DataExporterArrayFormat.getArrayFormat(
            CommonUtils.toString(properties.get(PROP_FORMAT_ARRAY), DEFAULT_ARRAY_BRACKETS));
    }

    @Override
    public void dispose()
    {
        super.dispose();
    }

    @Override
    protected DBDDisplayFormat getValueExportFormat(DBDAttributeBinding column) {
        if ((column.getDataKind() == DBPDataKind.NUMERIC && !formatNumbers) || column.getDataKind() == DBPDataKind.ARRAY) {
            return DBDDisplayFormat.NATIVE;
        }
        return super.getValueExportFormat(column);
    }

    @Override
    public void exportHeader(DBCSession session) throws DBException, IOException
    {
        columns = getSite().getAttributes();
        if (headerPosition == HeaderPosition.top || headerPosition == HeaderPosition.both) {
            if (headerFormat != HeaderFormat.label) {
                DBSEntity srcEntity = DBUtils.getAdapter(DBSEntity.class, getSite().getSource());
                DBExecUtils.bindAttributes(session, srcEntity, null, columns, null);
            }
            printHeader();
        }
    }

    private void printHeader()
    {
        for (int i = 0, columnsSize = columns.length; i < columnsSize; i++) {
            DBDAttributeBinding column = columns[i];
            String colName = column.getName();
            if (headerFormat == HeaderFormat.description) {
                colName = column.getDescription();
                if (colName == null) {
                    colName = column.getLabel();
                }
            } else {
                String colLabel = column.getLabel();
                if (CommonUtils.equalObjects(colLabel, colName)) {
                    colName = column.getParentObject() == null ? column.getName() : DBUtils.getObjectFullName(column, DBPEvaluationContext.UI);
                } else if (!CommonUtils.isEmpty(colLabel)) {
                    // Label has higher priority
                    colName = colLabel;
                }
                if (headerFormat == HeaderFormat.both) {
                    String description = column.getDescription();
                    if (!CommonUtils.isEmpty(description)) {
                        colName += ":" + description;
                    }
                }
            }
            writeCellValue(headerCase.transform(colName), true);
            if (i < columnsSize - 1) {
                writeDelimiter();
            }
        }
        writeRowLimit();
    }

    @Override
    public void exportRow(DBCSession session, DBCResultSet resultSet, Object[] row) throws DBException, IOException
    {
        for (int i = 0; i < row.length && i < columns.length; i++) {
            DBDAttributeBinding column = columns[i];
            if (row[i] instanceof DBDContent) {
                // Content
                // Inline textual content and handle binaries in some special way
                DBDContent content = (DBDContent)row[i];
                try {
                    DBDContentStorage cs = content.getContents(session.getProgressMonitor());
                    if (cs == null) {
                        writeCellValue(DBConstants.NULL_VALUE_LABEL, false);
                    } else if (ContentUtils.isTextContent(content)) {
                        writeCellValue(cs.getContentReader());
                    } else {
//                        out.write(quoteChar);
                        getSite().writeBinaryData(cs);
//                        out.write(quoteChar);
                    }
                }
                finally {
                    DTUtils.closeContents(resultSet, content);
                }
            } else {
                String stringValue = super.getValueDisplayString(column, row[i]);
                boolean quote = false;
                if (column.getDataKind() == DBPDataKind.ARRAY) {
                    stringValue = editArrayPrefixAndSuffix(dataExporterArrayFormat, stringValue);
                }

                if (quoteStrategy == QuoteStrategy.DISABLED) {
                    if (!stringValue.isEmpty() && !(row[i] instanceof Number) && !(row[i] instanceof Date) && Character.isDigit(stringValue.charAt(0))) {
                        // Quote string values which starts from number
                        quote = true;
                    }
                } else if (quoteStrategy == QuoteStrategy.STRINGS) {
                    if (!stringValue.isEmpty() && !(row[i] instanceof Number) && !(row[i] instanceof Date)) {
                        quote = true;
                    }
                } else if (quoteStrategy == QuoteStrategy.ALL_BUT_NUMBERS) {
                    if (!(row[i] instanceof Number)) {
                        quote = true;
                    }
                }

                if (DBUtils.isNullValue(row[i])) {
                    if (CommonUtils.isNotEmpty(nullString)) {
                        writeCellValue(nullString, quote);
                    } else if (quoteStrategy == QuoteStrategy.ALL_INCLUDING_NULLS) {
                        writeCellValue("", true);
                    }
                } else {
                    writeCellValue(stringValue, quote);
                }
            }
            if (i < row.length - 1) {
                writeDelimiter();
            }
        }
        writeRowLimit();
    }

    private String editArrayPrefixAndSuffix(DataExporterArrayFormat modifiedFormat, String stringValue) {
        if (stringValue == null || stringValue.isEmpty()) {
            return stringValue;
        }

        stringValue = stringValue.trim();

        DataExporterArrayFormat currentArrayFormat = DataExporterArrayFormat.getArrayFormatOnPrefix(stringValue.charAt(0));
        if (currentArrayFormat.equals(modifiedFormat)) {
            return stringValue;
        }

        boolean insideQuotes = false;
        StringBuilder modifiedBuilder = new StringBuilder();
        for (char c : stringValue.toCharArray()) {
            if (c == '"') {
                insideQuotes = !insideQuotes;
            }
            if (!insideQuotes) {
                if (c == currentArrayFormat.getPrefix()) {
                    modifiedBuilder.append(modifiedFormat.getPrefix());
                    continue;
                } else if (c == currentArrayFormat.getSuffix()) {
                    modifiedBuilder.append(modifiedFormat.getSuffix());
                    continue;
                }
            }
            modifiedBuilder.append(c);
        }
        return modifiedBuilder.toString();
    }

    @Override
    public void exportFooter(DBRProgressMonitor monitor) {
        if (headerPosition == HeaderPosition.bottom || headerPosition == HeaderPosition.both) {
            printHeader();
        }
    }

    @Override
    public void importData(@NotNull IStreamDataExporterSite site) {
        final Path file = site.getOutputFile();
        if (file == null || !Files.exists(file)) {
            return;
        }
        // FIXME: Sources may be different and thus may have a different set of attributes
        headerPosition = HeaderPosition.none;
    }

    @Override
    public boolean shouldTruncateOutputFileBeforeExport() {
        return false;
    }

    private void writeCellValue(String value, boolean quote)
    {
        if (!useQuotes) {
            quote = false;
        }
        // check for needed quote
        final boolean hasQuotes = useQuotes && value.indexOf(quoteChar) != -1;

        if (CommonUtils.isNotEmpty(lineFeedEscapeString)) {
            if (value.indexOf('\n') != -1) {
                value = LINE_BREAK_REGEX.matcher(value).replaceAll(lineFeedEscapeString);
            }
        }

        if (quoteStrategy == QuoteStrategy.ALL ||
            quoteStrategy == QuoteStrategy.ALL_INCLUDING_NULLS ||
            (useQuotes && value.isEmpty())
        ) {
            quote = true;
        } else if (!quote) {
            if (hasQuotes ||
                value.contains(delimiter) ||
                value.indexOf('\r') != -1 ||
                value.indexOf('\n') != -1 ||
                value.contains(rowDelimiter)
            ) {
                quote = true;
            }
        }

        if (quote && hasQuotes) {
            // escape quotes with double quotes
            buffer.setLength(0);
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == quoteChar) {
                    buffer.append(quoteChar);
                }
                buffer.append(c);
            }
            value = buffer.toString();
        }
        PrintWriter out = getWriter();
        if (quote && useQuotes) out.write(quoteChar);
        out.write(value);
        if (quote && useQuotes) out.write(quoteChar);
    }

    private void writeCellValue(Reader reader) throws IOException
    {
        try {
            PrintWriter out = getWriter();
            if (useQuotes) out.write(quoteChar);
            // Copy reader
            char[] buffer = new char[2000];
            for (;;) {
                int count = reader.read(buffer);
                if (count <= 0) {
                    break;
                }
                for (int i = 0; i < count; i++) {
                    if (useQuotes && buffer[i] == quoteChar) {
                        out.write(quoteChar);
                    }
                    out.write(buffer[i]);
                }
            }
            if (useQuotes) out.write(quoteChar);
        } finally {
            ContentUtils.close(reader);
        }
    }

    private void writeDelimiter()
    {
        getWriter().write(delimiter);
    }

    private void writeRowLimit()
    {
        getWriter().write(rowDelimiter);
    }

}
