/*

Copyright (C) 2012 NTT DATA Corporation

This program is free software; you can redistribute it and/or
Modify it under the terms of the GNU General Public License
as published by the Free Software Foundation, version 2.

This program is distributed in the hope that it will be
useful, but WITHOUT ANY WARRANTY; without even the implied
warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
PURPOSE.  See the GNU General Public License for more details.

 */

package com.clustercontrol.plugin.impl;

import java.util.HashSet;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.clustercontrol.maintenance.util.HinemosPropertyUtil;
import com.clustercontrol.plugin.api.HinemosPlugin;
import com.clustercontrol.ws.nodemap.NodeMapEndpoint;

/**
 * JAX-WSによるWEBサービスの初期化(publish)/停止(stop)を制御するノードマップオプション用プラグイン.
 *
 */
public class WebServiceNodeMapPlugin extends WebServiceOptionPlugin implements HinemosPlugin {

	public static final Log log = LogFactory.getLog(WebServiceNodeMapPlugin.class);

	@Override
	public Set<String> getDependency() {
		Set<String> dependency = new HashSet<String>();
		dependency.add(SnmpTrapPlugin.class.getName());
		dependency.add(SystemLogPlugin.class.getName());
		return dependency;
	}

	@Override
	public void create() {
	}

	@Override
	public void activate() {
		if (isNodeMap()) {
			final String addressPrefix = HinemosPropertyUtil.getHinemosPropertyStr("ws.client.address" , "http://0.0.0.0:8080");
			publish(addressPrefix, "/HinemosWS/NodeMapEndpoint", new NodeMapEndpoint());
		}
	}

	private boolean isNodeMap() {
		return isOption("nodemap.key", "e78d72f931f7d4f544022a68957b6a3b");
	}

}
