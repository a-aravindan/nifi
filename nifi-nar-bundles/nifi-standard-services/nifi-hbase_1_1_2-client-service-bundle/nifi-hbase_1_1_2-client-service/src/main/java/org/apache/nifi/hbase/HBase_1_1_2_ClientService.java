/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.hbase;

import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.ParseFilter;
import org.apache.nifi.annotation.behavior.DynamicProperty;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnDisabled;
import org.apache.nifi.annotation.lifecycle.OnEnabled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.controller.ControllerServiceInitializationContext;
import org.apache.nifi.hbase.put.PutFlowFile;
import org.apache.nifi.hbase.scan.Column;
import org.apache.nifi.hbase.scan.ResultCell;
import org.apache.nifi.hbase.scan.ResultHandler;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.reporting.InitializationException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tags({ "hbase", "client"})
@CapabilityDescription("Implementation of HBaseClientService for HBase 1.1.2. This service can be configured by providing " +
        "a comma-separated list of configuration files, or by specifying values for the other properties. If configuration files " +
        "are provided, they will be loaded first, and the values of the additional properties will override the values from " +
        "the configuration files. In addition, any user defined properties on the processor will also be passed to the HBase " +
        "configuration.")
@DynamicProperty(name="The name of an HBase configuration property.", value="The value of the given HBase configuration property.",
        description="These properties will be set on the HBase configuration after loading any provided configuration files.")
public class HBase_1_1_2_ClientService extends AbstractControllerService implements HBaseClientService {

    static final String HBASE_CONF_ZK_QUORUM = "hbase.zookeeper.quorum";
    static final String HBASE_CONF_ZK_PORT = "hbase.zookeeper.property.clientPort";
    static final String HBASE_CONF_ZNODE_PARENT = "zookeeper.znode.parent";
    static final String HBASE_CONF_CLIENT_RETRIES = "hbase.client.retries.number";

    private volatile Connection connection;
    private List<PropertyDescriptor> properties;

    @Override
    protected void init(ControllerServiceInitializationContext config) throws InitializationException {
        List<PropertyDescriptor> props = new ArrayList<>();
        props.add(HADOOP_CONF_FILES);
        props.add(ZOOKEEPER_QUORUM);
        props.add(ZOOKEEPER_CLIENT_PORT);
        props.add(ZOOKEEPER_ZNODE_PARENT);
        props.add(HBASE_CLIENT_RETRIES);
        this.properties = Collections.unmodifiableList(props);
    }

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return properties;
    }

    @Override
    protected PropertyDescriptor getSupportedDynamicPropertyDescriptor(String propertyDescriptorName) {
        return new PropertyDescriptor.Builder()
                .description("Specifies the value for '" + propertyDescriptorName + "' in the HBase configuration.")
                .name(propertyDescriptorName)
                .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
                .dynamic(true)
                .build();
    }

    @Override
    protected Collection<ValidationResult> customValidate(ValidationContext validationContext) {
        boolean confFileProvided = validationContext.getProperty(HADOOP_CONF_FILES).isSet();
        boolean zkQuorumProvided = validationContext.getProperty(ZOOKEEPER_QUORUM).isSet();
        boolean zkPortProvided = validationContext.getProperty(ZOOKEEPER_CLIENT_PORT).isSet();
        boolean znodeParentProvided = validationContext.getProperty(ZOOKEEPER_ZNODE_PARENT).isSet();
        boolean retriesProvided = validationContext.getProperty(HBASE_CLIENT_RETRIES).isSet();

        final List<ValidationResult> problems = new ArrayList<>();

        if (!confFileProvided && (!zkQuorumProvided || !zkPortProvided || !znodeParentProvided || !retriesProvided)) {
            problems.add(new ValidationResult.Builder()
                    .valid(false)
                    .subject(this.getClass().getSimpleName())
                    .explanation("ZooKeeper Quorum, ZooKeeper Client Port, ZooKeeper ZNode Parent, and HBase Client Retries are required " +
                            "when Hadoop Configuration Files are not provided.")
                    .build());
        }

        return problems;
    }

    @OnEnabled
    public void onEnabled(final ConfigurationContext context) throws InitializationException, IOException {
        this.connection = createConnection(context);

        // connection check
        if (this.connection != null) {
            final Admin admin = this.connection.getAdmin();
            if (admin != null) {
                admin.listTableNames();
            }
        }
    }

    protected Connection createConnection(final ConfigurationContext context) throws IOException {
        final Configuration hbaseConfig = HBaseConfiguration.create();

        // if conf files are provided, start with those
        if (context.getProperty(HADOOP_CONF_FILES).isSet()) {
            for (final String configFile : context.getProperty(HADOOP_CONF_FILES).getValue().split(",")) {
                hbaseConfig.addResource(new Path(configFile.trim()));
            }
        }

        // override with any properties that are provided
        if (context.getProperty(ZOOKEEPER_QUORUM).isSet()) {
            hbaseConfig.set(HBASE_CONF_ZK_QUORUM, context.getProperty(ZOOKEEPER_QUORUM).getValue());
        }
        if (context.getProperty(ZOOKEEPER_CLIENT_PORT).isSet()) {
            hbaseConfig.set(HBASE_CONF_ZK_PORT, context.getProperty(ZOOKEEPER_CLIENT_PORT).getValue());
        }
        if (context.getProperty(ZOOKEEPER_ZNODE_PARENT).isSet()) {
            hbaseConfig.set(HBASE_CONF_ZNODE_PARENT, context.getProperty(ZOOKEEPER_ZNODE_PARENT).getValue());
        }
        if (context.getProperty(HBASE_CLIENT_RETRIES).isSet()) {
            hbaseConfig.set(HBASE_CONF_CLIENT_RETRIES, context.getProperty(HBASE_CLIENT_RETRIES).getValue());
        }

        // add any dynamic properties to the HBase configuration
        for (final Map.Entry<PropertyDescriptor, String> entry : context.getProperties().entrySet()) {
            final PropertyDescriptor descriptor = entry.getKey();
            if (descriptor.isDynamic()) {
                hbaseConfig.set(descriptor.getName(), entry.getValue());
            }
        }

        return ConnectionFactory.createConnection(hbaseConfig);
    }

    @OnDisabled
    public void shutdown() {
        if (connection != null) {
            try {
                connection.close();
            } catch (final IOException ioe) {
                getLogger().warn("Failed to close connection to HBase due to {}", new Object[]{ioe});
            }
        }
    }

    @Override
    public void put(final String tableName, final Collection<PutFlowFile> puts) throws IOException {
        try (final Table table = connection.getTable(TableName.valueOf(tableName))) {
            // Create one Put per row....
            final Map<String, Put> rowPuts = new HashMap<>();
            for (final PutFlowFile putFlowFile : puts) {
                Put put = rowPuts.get(putFlowFile.getRow());
                if (put == null) {
                    put = new Put(putFlowFile.getRow().getBytes(StandardCharsets.UTF_8));
                    rowPuts.put(putFlowFile.getRow(), put);
                }
                put.addColumn(putFlowFile.getColumnFamily().getBytes(StandardCharsets.UTF_8),
                        putFlowFile.getColumnQualifier().getBytes(StandardCharsets.UTF_8),
                        putFlowFile.getBuffer());
            }

            table.put(new ArrayList<>(rowPuts.values()));
        }
    }

    @Override
    public void scan(final String tableName, final Collection<Column> columns, final String filterExpression, final long minTime, final ResultHandler handler)
            throws IOException {

        Filter filter = null;
        if (!StringUtils.isBlank(filterExpression)) {
            ParseFilter parseFilter = new ParseFilter();
            filter = parseFilter.parseFilterString(filterExpression);
        }

        try (final Table table = connection.getTable(TableName.valueOf(tableName));
             final ResultScanner scanner = getResults(table, columns, filter, minTime)) {

            for (final Result result : scanner) {
                final byte[] rowKey = result.getRow();
                final Cell[] cells = result.rawCells();

                if (cells == null) {
                    continue;
                }

                // convert HBase cells to NiFi cells
                final ResultCell[] resultCells = new ResultCell[cells.length];

                for (int i=0; i < cells.length; i++) {
                    final Cell cell = cells[i];

                    final ResultCell resultCell = new ResultCell();
                    resultCell.setRowArray(cell.getRowArray());
                    resultCell.setRowOffset(cell.getRowOffset());
                    resultCell.setRowLength(cell.getRowLength());

                    resultCell.setFamilyArray(cell.getFamilyArray());
                    resultCell.setFamilyOffset(cell.getFamilyOffset());
                    resultCell.setFamilyLength(cell.getFamilyLength());

                    resultCell.setQualifierArray(cell.getQualifierArray());
                    resultCell.setQualifierOffset(cell.getQualifierOffset());
                    resultCell.setQualifierLength(cell.getQualifierLength());

                    resultCell.setTimestamp(cell.getTimestamp());
                    resultCell.setTypeByte(cell.getTypeByte());
                    resultCell.setSequenceId(cell.getSequenceId());

                    resultCell.setValueArray(cell.getValueArray());
                    resultCell.setValueOffset(cell.getValueOffset());
                    resultCell.setValueLength(cell.getValueLength());

                    resultCell.setTagsArray(cell.getTagsArray());
                    resultCell.setTagsOffset(cell.getTagsOffset());
                    resultCell.setTagsLength(cell.getTagsLength());

                    resultCells[i] = resultCell;
                }

                // delegate to the handler
                handler.handle(rowKey, resultCells);
            }
        }
    }

    // protected and extracted into separate method for testing
    protected ResultScanner getResults(final Table table, final Collection<Column> columns, final Filter filter, final long minTime) throws IOException {
        // Create a new scan. We will set the min timerange as the latest timestamp that
        // we have seen so far. The minimum timestamp is inclusive, so we will get duplicates.
        // We will record any cells that have the latest timestamp, so that when we scan again,
        // we know to throw away those duplicates.
        final Scan scan = new Scan();
        scan.setTimeRange(minTime, Long.MAX_VALUE);

        if (filter != null) {
            scan.setFilter(filter);
        }

        if (columns != null) {
            for (Column col : columns) {
                if (col.getQualifier() == null) {
                    scan.addFamily(col.getFamily());
                } else {
                    scan.addColumn(col.getFamily(), col.getQualifier());
                }
            }
        }

        return table.getScanner(scan);
    }

}