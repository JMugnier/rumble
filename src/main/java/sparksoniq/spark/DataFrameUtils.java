package sparksoniq.spark;

import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import org.rumbledb.api.Item;
import org.rumbledb.exceptions.MLInvalidDataFrameSchemaException;
import org.rumbledb.items.ObjectItem;
import org.rumbledb.items.parsing.ItemParser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DataFrameUtils {
    public static Dataset<Row> convertItemRDDToDataFrame(
            JavaRDD<Item> itemRDD,
            ObjectItem schemaItem
    ) {
        ObjectItem firstDataItem = (ObjectItem) itemRDD.take(1).get(0);
        validateSchemaAgainstAnItem(schemaItem, firstDataItem);
        StructType schema = generateDataFrameSchemaFromSchemaItem(schemaItem);
        JavaRDD<Row> rowRDD = itemRDD.map(
            new Function<Item, Row>() {
                private static final long serialVersionUID = 1L;

                @Override
                public Row call(Item item) {
                    return ItemParser.getRowFromItem(item);
                }
            }
        );
        try {
            Dataset<Row> result = SparkSessionManager.getInstance()
                .getOrCreateSession()
                .createDataFrame(rowRDD, schema);
            result.take(1);
            return result;
        } catch (ClassCastException | IllegalArgumentException ex) {
            throw new MLInvalidDataFrameSchemaException("Error while applying the schema: " + ex.getMessage());
        }
    }

    public static Dataset<Row> convertLocalItemsToDataFrame(
            List<Item> items,
            ObjectItem schemaItem
    ) {
        ObjectItem firstDataItem = (ObjectItem) items.get(0);
        validateSchemaAgainstAnItem(schemaItem, firstDataItem);
        StructType schema = generateDataFrameSchemaFromSchemaItem(schemaItem);
        List<Row> rows = ItemParser.getRowsFromItems(items);
        try {
            Dataset<Row> result = SparkSessionManager.getInstance().getOrCreateSession().createDataFrame(rows, schema);
            result.take(1);
            return result;
        } catch (ClassCastException | IllegalArgumentException ex) {
            throw new MLInvalidDataFrameSchemaException("Error while applying the schema: " + ex.getMessage());
        }
    }

    private static void validateSchemaAgainstAnItem(
            ObjectItem schemaItem,
            ObjectItem dataItem
    ) {
        for (String schemaColumn : schemaItem.getKeys()) {
            if (!dataItem.getKeys().contains(schemaColumn)) {
                throw new MLInvalidDataFrameSchemaException(
                        "Columns defined in schema must fully match the columns of input data: "
                            + "missing type information for '"
                            + schemaColumn
                            + "' column."
                );
            }
        }

        for (String dataColumn : dataItem.getKeys()) {
            if (!schemaItem.getKeys().contains(dataColumn)) {
                throw new MLInvalidDataFrameSchemaException(
                        "Columns defined in schema must fully match the columns of input data: "
                            + "redundant type information for non-existent column '"
                            + dataColumn
                            + "'."
                );
            }
        }
    }

    private static StructType generateDataFrameSchemaFromSchemaItem(ObjectItem schemaItem) {
        List<StructField> fields = new ArrayList<>();
        try {
            for (String columnName : schemaItem.getKeys()) {
                StructField field = generateStructFieldFromNameAndItem(columnName, schemaItem.getItemByKey(columnName));
                fields.add(field);
            }
        } catch (IllegalArgumentException ex) {
            throw new MLInvalidDataFrameSchemaException(
                    "Error while applying the schema: " + ex.getMessage()
            );
        }
        return DataTypes.createStructType(fields);
    }

    private static StructField generateStructFieldFromNameAndItem(String columnName, Item item) {
        DataType type = generateDataTypeFromItem(item);
        return DataTypes.createStructField(columnName, type, true);
    }

    private static DataType generateDataTypeFromItem(Item item) {
        if (item.isArray()) {
            validateArrayItemInSchema(item);
            Item arrayContentsTypeItem = item.getItems().get(0);
            DataType arrayContentsType = generateDataTypeFromItem(arrayContentsTypeItem);
            return DataTypes.createArrayType(arrayContentsType);
        }

        if (item.isObject()) {
            // TODO: implement object support
            // createStructType(....)
        }

        if (item.isString()) {
            String itemTypeName = item.getStringValue();
            return ItemParser.getDataFrameDataTypeFromItemTypeString(itemTypeName);
        }

        throw new MLInvalidDataFrameSchemaException(
                "Schema can only contain arrays, objects or strings: " + item.toString() + " is not accepted"
        );
    }

    private static void validateArrayItemInSchema(Item item) {
        List<Item> arrayTypes = item.getItems();
        if (arrayTypes.size() == 0) {
            throw new MLInvalidDataFrameSchemaException(
                    "Arrays in schema must define a type for their contents."
            );
        }
        if (arrayTypes.size() > 1) {
            throw new MLInvalidDataFrameSchemaException(
                    "Arrays in schema can define only a single type for their contents: "
                        + item.toString()
                        + " is invalid."
            );
        }
    }

    public static void validateSchemaAgainstDataFrame(
            ObjectItem schemaItem,
            StructType dataFrameSchema
    ) {
        for (StructField column : dataFrameSchema.fields()) {
            final String columnName = column.name();
            final DataType columnDataType = column.dataType();
            boolean columnMatched = schemaItem.getKeys().stream().anyMatch(userSchemaColumnName -> {
                if (!columnName.equals(userSchemaColumnName)) {
                    return false;
                }

                String userSchemaColumnTypeName = schemaItem.getItemByKey(userSchemaColumnName).getStringValue();
                DataType userSchemaColumnDataType;
                try {
                    userSchemaColumnDataType = ItemParser.getDataFrameDataTypeFromItemTypeString(
                        userSchemaColumnTypeName
                    );
                } catch (IllegalArgumentException ex) {
                    throw new MLInvalidDataFrameSchemaException(
                            "Error while applying the schema: " + ex.getMessage()
                    );
                }

                if (isUserTypeApplicable(userSchemaColumnDataType, columnDataType)) {
                    return true;
                }

                throw new MLInvalidDataFrameSchemaException(
                        "Columns defined in schema must fully match the columns of input data: "
                            + "expected '"
                            + ItemParser.getItemTypeNameFromDataFrameDataType(columnDataType)
                            + "' type for column '"
                            + columnName
                            + "', but found '"
                            + userSchemaColumnTypeName
                            + "'"
                );
            });

            if (!columnMatched) {
                throw new MLInvalidDataFrameSchemaException(
                        "Columns defined in schema must fully match the columns of input data: "
                            + "missing type information for '"
                            + columnName
                            + "' column."
                );
            }
        }

        for (String userSchemaColumnName : schemaItem.getKeys()) {
            boolean userColumnMatched = Arrays.stream(dataFrameSchema.fields())
                .anyMatch(
                    structField -> userSchemaColumnName.equals(structField.name())
                );

            if (!userColumnMatched) {
                throw new MLInvalidDataFrameSchemaException(
                        "Columns defined in schema must fully match the columns of input data: "
                            + "redundant type information for non-existent column '"
                            + userSchemaColumnName
                            + "'."
                );
            }
        }
    }

    private static boolean isUserTypeApplicable(DataType userSchemaColumnDataType, DataType columnDataType) {
        return userSchemaColumnDataType.equals(columnDataType)
            ||
            (userSchemaColumnDataType.equals(DataTypes.DoubleType) && columnDataType.equals(DataTypes.LongType))
            ||
            (userSchemaColumnDataType.equals(DataTypes.DoubleType) && columnDataType.equals(DataTypes.FloatType))
            ||
            (userSchemaColumnDataType.equals(DataTypes.IntegerType) && columnDataType.equals(DataTypes.ShortType));
    }
}
