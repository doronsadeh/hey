package com.colderlazarus.hey.dynamodb;

import android.content.Context;
import android.util.Log;

import com.amazonaws.auth.CognitoCachingCredentialsProvider;
import com.amazonaws.mobileconnectors.dynamodbv2.document.QueryFilter;
import com.amazonaws.mobileconnectors.dynamodbv2.document.Table;
import com.amazonaws.mobileconnectors.dynamodbv2.document.datatype.Document;
import com.amazonaws.mobileconnectors.dynamodbv2.document.datatype.Primitive;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.ComparisonOperator;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class DynamoDBAPI {
    private static final String TAG = "hey.DynamoDBAPI";

    private static final String TTL = "ttl";

    private static final long DYNAMODB_GET_ITEM_TIMEOUT_SEC = 5000;

    private AmazonDynamoDBClient client;
    private String tableName;

    private ExecutorService executor = Executors.newFixedThreadPool(1);

    public DynamoDBAPI(Context context, String tableName) {

        this.tableName = tableName;

        // Initialize the Amazon Cognito credentials provider
        CognitoCachingCredentialsProvider credentialsProvider = new CognitoCachingCredentialsProvider(
                context.getApplicationContext(),
                "eu-west-1:64a6553f-9b37-43b1-b5e7-05686c62dc74", // Identity pool ID
                Regions.EU_WEST_1 // Region
        );

        // Create a connection to DynamoDB
        client = new AmazonDynamoDBClient(credentialsProvider);
        client.setRegion(Region.getRegion(Regions.EU_WEST_1));
    }

    public Document getOne(String hashKey) {

        final String _hashKey = hashKey;
        Callable<Document> callback = new Callable<Document>() {
            @Override
            public Document call() throws Exception {
                Table riders_table = Table.loadTable(client, tableName);
                return riders_table.getItem(new Primitive(_hashKey));
            }
        };

        FutureTask<Document> fTask = new FutureTask<>(callback);

        // Run the item fetch
        executor.execute(fTask);

        try {
            return fTask.get(DYNAMODB_GET_ITEM_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Log.e(TAG, "Problem executing DynamoDB getItem: " + e.getMessage());
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted while executing DynamoDB getItem: " + e.getMessage());
        } catch (TimeoutException e) {
            Log.e(TAG, "Timed-out while executing DynamoDB getItem: " + e.getMessage());
        }

        return null;  
    }

    public List<Document> get(String hashKey, long currentTimeSec) {
        List<AttributeValue> attrValues = new ArrayList<>();
        attrValues.add((new AttributeValue()).withN(String.valueOf(currentTimeSec)));
        final QueryFilter qFilter = new QueryFilter(
                TTL,
                ComparisonOperator.GT,
                attrValues);

        final String _hashKey = hashKey;
        Callable<List<Document>> callback = new Callable<List<Document>>() {
            @Override
            public List<Document> call() throws Exception {
                Table riders_table = Table.loadTable(client, tableName);
                return riders_table.query(new Primitive(_hashKey), qFilter).getAllResults();
            }
        };

        FutureTask<List<Document>> fTask = new FutureTask<>(callback);

        // Run the item fetch
        executor.execute(fTask);

        try {
            return fTask.get(DYNAMODB_GET_ITEM_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Log.e(TAG, "Problem executing DynamoDB query: " + e.getMessage());
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted while executing DynamoDB query: " + e.getMessage());
        } catch (TimeoutException e) {
            Log.e(TAG, "Timed-out while executing DynamoDB query: " + e.getMessage());
        }

        return null;
    }

    public void put(Document item) {
        final Document _item = item;
        // Save the item
        Callable<Void> callback = new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                Table table = Table.loadTable(client, tableName);
                table.putItem(_item);
                return null;
            }
        };

        FutureTask<Void> fTask = new FutureTask<>(callback);

        // Run the item write
        executor.execute(fTask);
    }

    public void delete(String riderId) {
        Table.loadTable(client, tableName).deleteItem(new Primitive(riderId));
    }
}

