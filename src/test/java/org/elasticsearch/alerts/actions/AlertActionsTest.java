/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.alerts.actions;


import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.ShardSearchFailure;
import org.elasticsearch.alerts.Alert;
import org.elasticsearch.alerts.AlertManager;
import org.elasticsearch.alerts.AlertUtils;
import org.elasticsearch.alerts.AlertsStore;
import org.elasticsearch.alerts.client.AlertsClient;
import org.elasticsearch.alerts.plugin.AlertsPlugin;
import org.elasticsearch.alerts.transport.actions.delete.DeleteAlertRequest;
import org.elasticsearch.alerts.transport.actions.delete.DeleteAlertResponse;
import org.elasticsearch.alerts.transport.actions.get.GetAlertRequest;
import org.elasticsearch.alerts.transport.actions.get.GetAlertResponse;
import org.elasticsearch.alerts.transport.actions.put.PutAlertRequest;
import org.elasticsearch.alerts.transport.actions.put.PutAlertResponse;
import org.elasticsearch.alerts.triggers.AlertTrigger;
import org.elasticsearch.alerts.triggers.ScriptedTrigger;
import org.elasticsearch.alerts.triggers.TriggerResult;
import org.elasticsearch.common.joda.FormatDateTimeFormatter;
import org.elasticsearch.common.joda.time.DateTime;
import org.elasticsearch.common.joda.time.DateTimeZone;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.index.mapper.core.DateFieldMapper;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.search.internal.InternalSearchHit;
import org.elasticsearch.search.internal.InternalSearchHits;
import org.elasticsearch.search.internal.InternalSearchResponse;
import org.elasticsearch.test.ElasticsearchIntegrationTest;
import org.junit.Test;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.hamcrest.core.Is.is;

/**
 */
@ElasticsearchIntegrationTest.ClusterScope(scope = ElasticsearchIntegrationTest.Scope.SUITE, numClientNodes = 0, transportClientRatio = 0, numDataNodes = 1)
public class AlertActionsTest extends ElasticsearchIntegrationTest {


    private static final FormatDateTimeFormatter formatter = DateFieldMapper.Defaults.DATE_TIME_FORMATTER;


    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return ImmutableSettings.builder()
                .put(super.nodeSettings(nodeOrdinal))
                .put("plugin.mandatory", "alerts")
                .put("plugin.types", AlertsPlugin.class.getName())
                .put("node.mode", "network")
                .put("plugins.load_classpath_plugins", false)
                .build();
    }



    @Test
    public void testAlertActionParser() throws Exception {
        DateTime fireTime = new DateTime(DateTimeZone.UTC);
        DateTime scheduledFireTime = new DateTime(DateTimeZone.UTC);

        Map<String, Object> triggerMap = new HashMap<>();
        Map<String, Object> scriptTriggerMap = new HashMap<>();
        scriptTriggerMap.put("script", "hits.total>1");
        scriptTriggerMap.put("script_lang", "groovy");
        triggerMap.put("script", scriptTriggerMap );


        Map<String,Object> actionMap = new HashMap<>();
        Map<String,Object> emailParamMap = new HashMap<>();
        List<String> addresses = new ArrayList<>();
        addresses.add("foo@bar.com");
        emailParamMap.put("addresses", addresses);
        actionMap.put("email", emailParamMap);

        XContentBuilder builder = jsonBuilder();
        builder.startObject();
        builder.field(AlertActionManager.ALERT_NAME_FIELD, "testName");
        builder.field(AlertActionManager.TRIGGERED_FIELD, true);
        builder.field(AlertActionManager.FIRE_TIME_FIELD, formatter.printer().print(fireTime));
        builder.field(AlertActionManager.SCHEDULED_FIRE_TIME_FIELD, formatter.printer().print(scheduledFireTime));
        builder.field(AlertActionManager.TRIGGER_FIELD, triggerMap);
        SearchRequest searchRequest = new SearchRequest("test123");
        builder.field(AlertActionManager.REQUEST);
        AlertUtils.writeSearchRequest(searchRequest, builder, ToXContent.EMPTY_PARAMS);
        SearchResponse searchResponse = new SearchResponse(
                new InternalSearchResponse(new InternalSearchHits(new InternalSearchHit[0], 10, 0), null, null, null, false, false),
                null, 1, 1, 0, new ShardSearchFailure[0]
        );
        builder.startObject(AlertActionManager.RESPONSE);
        builder.value(searchResponse);
        builder.endObject();
        builder.field(AlertActionManager.ACTIONS_FIELD, actionMap);
        builder.field(AlertActionState.FIELD_NAME, AlertActionState.SEARCH_NEEDED.toString());
        builder.endObject();
        final AlertActionRegistry alertActionRegistry = internalCluster().getInstance(AlertActionRegistry.class, internalCluster().getMasterName());
        final AlertActionManager alertManager = internalCluster().getInstance(AlertActionManager.class, internalCluster().getMasterName());

        AlertActionEntry actionEntry = alertManager.parseHistory("foobar", builder.bytes(), 0, alertActionRegistry);
        assertEquals(actionEntry.getVersion(), 0);
        assertEquals(actionEntry.getAlertName(), "testName");
        assertEquals(actionEntry.isTriggered(), true);
        assertEquals(actionEntry.getScheduledTime(), scheduledFireTime);
        assertEquals(actionEntry.getFireTime(), fireTime);
        assertEquals(actionEntry.getEntryState(), AlertActionState.SEARCH_NEEDED);
        assertEquals(XContentMapValues.extractValue("hits.total", actionEntry.getSearchResponse()), 10);
    }

    @Test
    public void testAlertActions() throws Exception {
        createIndex("my-index");
        createIndex(AlertsStore.ALERT_INDEX);
        createIndex(AlertActionManager.ALERT_HISTORY_INDEX);

        ensureGreen("my-index", AlertsStore.ALERT_INDEX, AlertActionManager.ALERT_HISTORY_INDEX);

        client().preparePutIndexedScript()
                .setScriptLang("mustache")
                .setId("query")
                .setSource(jsonBuilder().startObject().startObject("template").startObject("match_all").endObject().endObject().endObject())
                .get();

        final AlertManager alertManager = internalCluster().getInstance(AlertManager.class, internalCluster().getMasterName());
        assertBusy(new Runnable() {
            @Override
            public void run() {
                assertThat(alertManager.isStarted(), is(true));
            }
        });
        final AtomicBoolean alertActionInvoked = new AtomicBoolean(false);
        final AlertAction alertAction = new AlertAction() {
            @Override
            public String getActionName() {
                return "test";
            }

            @Override
            public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
                builder.startObject();
                builder.endObject();
                return builder;
            }

        };
        AlertActionRegistry alertActionRegistry = internalCluster().getInstance(AlertActionRegistry.class, internalCluster().getMasterName());
        alertActionRegistry.registerAction("test", new AlertActionFactory() {
            @Override
            public AlertAction createAction(XContentParser parser) throws IOException {
                parser.nextToken();
                return alertAction;
            }

            @Override
            public boolean doAction(AlertAction action, Alert alert, TriggerResult actionEntry) {
                logger.info("Alert {} invoked: {}", alert.alertName(), actionEntry);
                alertActionInvoked.set(true);
                return true;
            }

        });

        AlertTrigger alertTrigger = new ScriptedTrigger("return true", ScriptService.ScriptType.INLINE, "groovy");


        Alert alert = new Alert(
                "my-first-alert",
                client().prepareSearch("my-index").setQuery(QueryBuilders.matchAllQuery()).request(),
                alertTrigger,
                Arrays.asList(alertAction),
                "0/5 * * * * ? *",
                null,
                1,
                true
        );

        XContentBuilder jsonBuilder = XContentFactory.jsonBuilder();
        alert.toXContent(jsonBuilder, ToXContent.EMPTY_PARAMS);

        AlertsClient alertsClient = internalCluster().getInstance(AlertsClient.class, internalCluster().getMasterName());

        PutAlertRequest alertRequest = alertsClient.prepareIndexAlert().setAlertName("my-first-alert").setAlertSource(jsonBuilder.bytes()).request();
        PutAlertResponse alertsResponse = alertsClient.indexAlert(alertRequest).actionGet();
        assertNotNull(alertsResponse.indexResponse());
        assertTrue(alertsResponse.indexResponse().isCreated());

        GetAlertRequest getAlertRequest = new GetAlertRequest(alert.alertName());
        GetAlertResponse getAlertResponse = alertsClient.getAlert(getAlertRequest).actionGet();
        assertTrue(getAlertResponse.getResponse().isExists());
        assertEquals(getAlertResponse.getResponse().getSourceAsMap().get("schedule").toString(), "0/5 * * * * ? *");

        DeleteAlertRequest deleteAlertRequest = new DeleteAlertRequest(alert.alertName());
        DeleteAlertResponse deleteAlertResponse = alertsClient.deleteAlert(deleteAlertRequest).actionGet();
        assertNotNull(deleteAlertResponse.deleteResponse());
        assertTrue(deleteAlertResponse.deleteResponse().isFound());

        getAlertResponse = alertsClient.getAlert(getAlertRequest).actionGet();
        assertFalse(getAlertResponse.getResponse().isExists());

    }

}
