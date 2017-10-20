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
import hudson.model.BooleanParameterValue;
import hudson.model.TextParameterValue;
import hudson.model.PasswordParameterValue;
import hudson.model.queue.QueueTaskFuture;
import hudson.model.Queue;
import hudson.model.Item;
import hudson.model.ParameterDefinition;
import hudson.model.ParametersDefinitionProperty;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.lang.InterruptedException;

import org.jenkinsci.plugins.workflow.job.WorkflowJob;

//////TEMP

import hudson.ExtensionList;
import hudson.ExtensionPoint;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionListener;

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
            // sample job creation request from a toolchain
            if (incomingJob.has("jobType") && "new".equalsIgnoreCase(incomingJob.get("jobType").toString())) {
            	log.info(logPrefix + "Job creation request received.");
            	// delegating job creation to the Jenkins server
            	JenkinsServer.createJob(incomingJob);
        	}
            
            if (incomingJob.has("fullName")) {
                String fullName = incomingJob.get("fullName").toString();
                Jenkins myJenkins = Jenkins.getInstance();

                Item item = myJenkins.getItem(fullName);

                List<ParameterValue> parametersList = generateParamList(incomingJob, getParameterTypeMap(item));

                JSONObject returnProps = new JSONObject();
                if(incomingJob.has("returnProps")) {
                    returnProps = incomingJob.getJSONObject("returnProps");
                }

                if(item instanceof AbstractProject) {
                    AbstractProject abstractProject = (AbstractProject)item;

                    ParameterizedJobMixIn.scheduleBuild2(abstractProject, 0, new ParametersAction(parametersList), new CauseAction(new CloudCause(socket, incomingJob.get("id").toString(), returnProps)));
                } else if (item instanceof WorkflowJob) {
                    WorkflowJob workflowJob = (WorkflowJob)item;

                    workflowJob.scheduleBuild2(0, new ParametersAction(parametersList), new CauseAction(new CloudCause(socket, incomingJob.get("id").toString(), returnProps) ));
                } else {
                    log.warn("Unhandled job type found: " + item.getClass());
                }

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

    private List<ParameterValue> generateParamList (JSONObject incomingJob, Map<String, String> typeMap) {
        ArrayList<ParameterValue> result = new ArrayList<ParameterValue>();

        if(incomingJob.has("props")) {
            JSONObject props = incomingJob.getJSONObject("props");
            Iterator<String> keys = props.keys();
            while( keys.hasNext() ) {
                String key = (String)keys.next();
                Object value = props.get(key);
                String type = typeMap.get(key);

                ParameterValue paramValue;

                if(type == null) {

                } else if(type.equalsIgnoreCase("BooleanParameterDefinition")) {
                    result.add(new BooleanParameterValue(key, (boolean)props.get(key)));
                } else if(type.equalsIgnoreCase("PasswordParameterDefinition")) {
                    result.add(new PasswordParameterValue(key, props.get(key).toString()));
                } else if(type.equalsIgnoreCase("TextParameterDefinition")) {
                    result.add(new TextParameterValue(key, props.get(key).toString()));
                } else {
                    result.add(new StringParameterValue(key, props.get(key).toString()));
                }
            }
        }

        return result;
    }

    private Map<String, String> getParameterTypeMap(Item item) {
        Map<String, String> result = new HashMap<String, String>();

        if(item instanceof AbstractProject) {
            List<Action> actions = ((AbstractProject)item).getActions();

			for(Action action : actions) {
				if (action instanceof ParametersDefinitionProperty) {
					List<ParameterDefinition> paraDefs = ((ParametersDefinitionProperty)action).getParameterDefinitions();
					for (ParameterDefinition paramDef : paraDefs) {
                        result.put(paramDef.getName(), paramDef.getType());
					}
				}
			}
        }

        return result;
    }
}
