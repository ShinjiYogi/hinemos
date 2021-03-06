package com.clustercontrol.infra.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.persistence.Cacheable;
import javax.persistence.Column;
import javax.persistence.DiscriminatorValue;
import javax.persistence.Entity;
import javax.persistence.Inheritance;
import javax.persistence.Table;

import org.apache.log4j.Logger;

import com.clustercontrol.commons.util.CommonValidator;
import com.clustercontrol.commons.util.HinemosEntityManager;
import com.clustercontrol.fault.HinemosUnknown;
import com.clustercontrol.fault.InvalidRole;
import com.clustercontrol.fault.InvalidSetting;
import com.clustercontrol.fault.InvalidUserPass;
import com.clustercontrol.infra.bean.AccessInfo;
import com.clustercontrol.infra.bean.AccessMethodConstant;
import com.clustercontrol.infra.bean.ModuleNodeResult;
import com.clustercontrol.infra.bean.OkNgConstant;
import com.clustercontrol.infra.util.JschUtil;
import com.clustercontrol.infra.util.QueryUtil;
import com.clustercontrol.infra.util.WinRMUtil;
import com.clustercontrol.repository.model.NodeInfo;
import com.clustercontrol.repository.util.RepositoryUtil;
import com.clustercontrol.util.MessageConstant;
import com.clustercontrol.util.StringBinder;

@Entity
@Table(name="cc_infra_command_module_info", schema="setting")
@Inheritance
@DiscriminatorValue(CommandModuleInfo.typeName)
@Cacheable(true)
public class CommandModuleInfo extends InfraModuleInfo<CommandModuleInfo> {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	public static final int MESSAGE_SIZE = 1024;

	public static final String typeName = "ExecModule";
	
	private int accessMethodType;
	private String execCommand;
	private String checkCommand;
	
	public CommandModuleInfo() {
	}
	
	public CommandModuleInfo(InfraManagementInfo parent, String moduleId) {
		super(parent, moduleId);
	}
	
	@Column(name="access_method_type")
	public int getAccessMethodType() {
		return accessMethodType;
	}
	public void setAccessMethodType(int accessMethodType) {
		this.accessMethodType = accessMethodType;
	}

	@Column(name="exec_command")
	public String getExecCommand() {
		return execCommand;
	}
	public void setExecCommand(String execCommand) {
		this.execCommand = execCommand;
	}

	@Column(name="check_command")
	public String getCheckCommand() {
		return checkCommand;
	}
	public void setCheckCommand(String checkCommand) {
		this.checkCommand = checkCommand;
	}
	
	@Override
	public String getModuleTypeName() {
		return typeName;
	}
	
	@Override
	public ModuleNodeResult run(InfraManagementInfo management, NodeInfo node, AccessInfo access, String sessionId) throws HinemosUnknown, InvalidUserPass {
		Logger.getLogger(this.getClass()).debug(String.format(String.format("%s %s, manegementId = %s, moduleId = %s", "start", "run", management.getManagementId(), getModuleId())));

		ModuleNodeResult result =  execCommand(node, getExecCommand(), access);
		
		Logger.getLogger(this.getClass()).debug(String.format(String.format("%s %s, manegementId = %s, moduleId = %s", "end", "run", management.getManagementId(), getModuleId())));
		
		return result;
	}

	@Override
	public ModuleNodeResult check(InfraManagementInfo management, NodeInfo node, AccessInfo access, String sessionId, boolean check) throws HinemosUnknown, InvalidUserPass {
		return execCommand(node, getCheckCommand(), access);
	}
	
	private ModuleNodeResult execCommand(NodeInfo node, String command, AccessInfo access) {
		String facilityId = node.getFacilityId();
		String address = node.getAvailableIpAddress();
		
		List<InfraFileInfo> fileList = QueryUtil.getAllInfraFile();

		// コマンド文字列の置換
		String bindCommand;
		try {
			HashMap<String, String> map = new HashMap<String, String>();
			
			// ノード変数
			Map<String, String> variable = RepositoryUtil.createNodeParameter(node);
			map.putAll(variable);
			
			// ファイルID			
			for (InfraFileInfo file : fileList) {
				String key = "FILE:" + file.getFileId();
				String value = file.getFileName();
				map.put(key, value);
				Logger.getLogger(this.getClass()).debug("execCommand()  >>> param.put = : " + key  + "  value = " +  value);
			}
			StringBinder binder = new StringBinder(map);
			bindCommand = binder.bindParam(command);
		} catch (Exception e) {
			Logger.getLogger(this.getClass()).warn("execCommand() : "
					+ e.getClass().getSimpleName() + ", " + e.getMessage(), e);
			bindCommand = command;
		}
		
		
		ModuleNodeResult ret = null;
		switch (getAccessMethodType()) {
		case AccessMethodConstant.TYPE_SSH:
			ret = JschUtil.execCommand(access.getSshUser(), access.getSshPassword(), address, access.getSshPort(),
					access.getSshTimeout(), bindCommand, MESSAGE_SIZE, access.getSshPrivateKeyFilepath(), access.getSshPrivateKeyPassphrase());
			break;
		case AccessMethodConstant.TYPE_WINRM:
			ret = WinRMUtil.execCommand(access.getWinRmUser(), access.getWinRmPassword(), address, access.getWinRmPort(),
					node.getWinrmProtocol(), bindCommand, MESSAGE_SIZE);
			break;
		default:
			String msg = String.format("AccessMethodType is invalid. value = %d", getAccessMethodType());
			Logger.getLogger(this.getClass()).warn("execCommand : " + msg);
			ret = new ModuleNodeResult(OkNgConstant.TYPE_NG, -1, msg);
		}
		ret.setFacilityId(facilityId);
		return ret;
	}

	@Override
	protected void validateSub(InfraManagementInfo infraManagementInfo) throws InvalidSetting, InvalidRole {
		// execCommand
		CommonValidator.validateString(MessageConstant.INFRA_MODULE_EXEC_COMMAND.getMessage(), getExecCommand(), false, 0, 1024);
		
		// checkCommand
		CommonValidator.validateString(MessageConstant.INFRA_MODULE_CHECK_COMMAND.getMessage(), getCheckCommand(), false, 0, 1024);
		
		// accessMethodType
		boolean match = false;
		for (int type: AccessMethodConstant.getTypeList()) {
			if (type == getAccessMethodType()) {
				match = true;
				break;
			}
		}
		if (!match) {
			InvalidSetting e = new InvalidSetting("AccessMethodType must be SSH(0) / WinRM(1).");
			Logger.getLogger(this.getClass()).info("validateSub() : " + e.getClass().getSimpleName() + ", " + e.getMessage());
			throw e;
		}
	}

	@Override
	public boolean canPrecheck(InfraManagementInfo management, NodeInfo node, AccessInfo access, String sessionId) throws HinemosUnknown, InvalidUserPass {
		return this.getPrecheckFlg();
	}
	
	@Override
	public void beforeRun(String sessionId) {
		//Do Nothing
	}
	
	@Override
	public void afterRun(String sessionId) {
		//Do Nothing
	}

	@Override
	protected Class<CommandModuleInfo> getEntityClass() {
		return CommandModuleInfo.class;
	}

	@Override
	protected void overwriteCounterEntity(InfraManagementInfo management, CommandModuleInfo module, HinemosEntityManager em) {
		module.setAccessMethodType(getAccessMethodType());
		module.setExecCommand(getExecCommand());
		module.setCheckCommand(getCheckCommand());
	}
}