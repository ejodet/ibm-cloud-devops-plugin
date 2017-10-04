/*******************************************************************************
 * Licensed Materials - Property of IBM
 * (c) Copyright IBM Corporation 2017. All Rights Reserved.
 *
 * Note to U.S. Government Users Restricted Rights:  Use,
 * duplication or disclosure restricted by GSA ADP Schedule
 * Contract with IBM Corp.
 *******************************************************************************/
package com.ibm.devops.connect;

import java.util.concurrent.TimeUnit;

// import org.json.JSONArray;
// import org.json.JSONException;
// import org.json.JSONObject;

import org.apache.commons.lang.builder.ToStringBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.ibm.cloud.urbancode.connect.client.ConnectSocket;

import net.sf.json.*;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.ParametersAction;
import hudson.model.CauseAction;
import hudson.model.ParameterValue;
import hudson.model.StringParameterValue;
import hudson.model.queue.QueueTaskFuture;
import hudson.model.Queue;

import java.util.ArrayList;
import java.util.Iterator;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.lang.InterruptedException;

/*
 * When Spring is applying the @Transactional annotation, it creates a proxy class which wraps your class.
 * So when your bean is created in your application context, you are getting an object that is not of type
 * WorkListener but some proxy class that implements the IWorkListener interface. So anywhere you want WorkListener
 * injected, you must use IWorkListener.
 */
public class CloudWorkListener implements IWorkListener {
	public static final Logger log = LoggerFactory.getLogger(CloudWorkListener.class);
    private String logPrefix= "[IBM Cloud DevOps] CloudWorkListener#";
    
    public CloudWorkListener() {

    }

    public enum WorkStatus {
        success, failed, started
    }

    /* (non-Javadoc)
     * @see com.ibm.cloud.urbancode.sync.IWorkListener#call(com.ibm.cloud.urbancode.connect.client.ConnectSocket, java.lang.String, java.lang.Object)
     */
    @Override
    public void call(ConnectSocket socket, String event, Object... args) {
        log.info(logPrefix + " Received event from Connect Socket");

        JSONArray incomingJobs = JSONArray.fromObject(args[0].toString());

        for(int i=0; i < incomingJobs.size(); i++) {
            JSONObject incomingJob = incomingJobs.getJSONObject(i);

            if (incomingJob.has("fullName")) {
                String fullName = incomingJob.get("fullName").toString();
                Jenkins myJenkins = Jenkins.getInstance();
                AbstractProject abstractProject = (AbstractProject)myJenkins.getItem(fullName);
                ArrayList<ParameterValue> parametersList = new ArrayList<ParameterValue>();

                if(incomingJob.has("props")) {
                    JSONObject props = incomingJob.getJSONObject("props");
                    Iterator<String> keys = props.keys();

                    while( keys.hasNext() ) {
                        String key = (String)keys.next();

                        parametersList.add(new StringParameterValue(key, props.get(key).toString()));
                    }
                }

                JSONObject returnProps = new JSONObject();
                if(incomingJob.has("returnProps")) {
                    returnProps = incomingJob.getJSONObject("returnProps");
                }

                Queue.Item queueItem = ParameterizedJobMixIn.scheduleBuild2(abstractProject, 0, new ParametersAction(parametersList), new CauseAction(
                    new CloudCause(socket, incomingJob.get("id").toString(), returnProps) ));

            }

            sendResult(socket, incomingJobs.getJSONObject(i).get("id").toString(), WorkStatus.started, "This work has been started");
        }

    }

    private void sendResult(ConnectSocket socket, String id, WorkStatus status, String comment) {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id);
            json.put("status", status.name());
            json.put("description", comment);
        }
        catch (JSONException e) {
            throw new RuntimeException("Error constructing work result JSON", e);
        }
        socket.emit("set_work_status", json.toString());
    }
}
