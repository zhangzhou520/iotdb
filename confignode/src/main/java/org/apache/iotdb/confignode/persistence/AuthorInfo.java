/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.confignode.persistence;

import org.apache.iotdb.common.rpc.thrift.TSStatus;
import org.apache.iotdb.commons.auth.AuthException;
import org.apache.iotdb.commons.auth.authorizer.BasicAuthorizer;
import org.apache.iotdb.commons.auth.authorizer.IAuthorizer;
import org.apache.iotdb.commons.auth.entity.PathPrivilege;
import org.apache.iotdb.commons.auth.entity.PrivilegeType;
import org.apache.iotdb.commons.auth.entity.Role;
import org.apache.iotdb.commons.auth.entity.User;
import org.apache.iotdb.commons.conf.CommonConfig;
import org.apache.iotdb.commons.conf.CommonDescriptor;
import org.apache.iotdb.commons.conf.IoTDBConstant;
import org.apache.iotdb.commons.snapshot.SnapshotProcessor;
import org.apache.iotdb.commons.utils.AuthUtils;
import org.apache.iotdb.commons.utils.FileUtils;
import org.apache.iotdb.commons.utils.TestOnly;
import org.apache.iotdb.confignode.consensus.request.ConfigRequestType;
import org.apache.iotdb.confignode.consensus.request.auth.AuthorReq;
import org.apache.iotdb.confignode.consensus.response.PermissionInfoResp;
import org.apache.iotdb.rpc.RpcUtils;
import org.apache.iotdb.rpc.TSStatusCode;

import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AuthorInfo implements SnapshotProcessor {

  private static final Logger logger = LoggerFactory.getLogger(AuthorInfo.class);
  private static final CommonConfig commonConfig = CommonDescriptor.getInstance().getConfig();

  private IAuthorizer authorizer;

  {
    try {
      authorizer = BasicAuthorizer.getInstance();
    } catch (AuthException e) {
      logger.error("get user or role permissionInfo failed", e);
    }
  }

  public TSStatus login(String username, String password) {
    boolean status;
    String loginMessage = null;
    TSStatus tsStatus = new TSStatus();
    try {
      status = authorizer.login(username, password);
    } catch (AuthException e) {
      logger.info("meet error while logging in.", e);
      status = false;
      loginMessage = e.getMessage();
    }
    if (status) {
      tsStatus.setCode(TSStatusCode.SUCCESS_STATUS.getStatusCode());
      tsStatus.setMessage("Login successfully");
    } else {
      tsStatus.setMessage(loginMessage != null ? loginMessage : "Authentication failed.");
      tsStatus.setCode(TSStatusCode.WRONG_LOGIN_PASSWORD_ERROR.getStatusCode());
    }
    return tsStatus;
  }

  public TSStatus checkUserPrivileges(String username, List<String> paths, int permission) {
    boolean status = true;
    try {
      for (String path : paths) {
        if (!checkOnePath(username, path, permission)) {
          status = false;
        }
      }
    } catch (AuthException e) {
      status = false;
    }
    if (status) {
      return RpcUtils.getStatus(TSStatusCode.SUCCESS_STATUS);
    } else {
      return RpcUtils.getStatus(TSStatusCode.NO_PERMISSION_ERROR);
    }
  }

  private boolean checkOnePath(String username, String path, int permission) throws AuthException {
    try {
      if (authorizer.checkUserPrivileges(username, path, permission)) {
        return true;
      }
    } catch (AuthException e) {
      logger.error("Error occurs when checking the seriesPath {} for user {}", path, username, e);
      throw new AuthException(e);
    }
    return false;
  }

  public TSStatus authorNonQuery(AuthorReq authorReq) {
    ConfigRequestType authorType = authorReq.getAuthorType();
    String userName = authorReq.getUserName();
    String roleName = authorReq.getRoleName();
    String password = authorReq.getPassword();
    String newPassword = authorReq.getNewPassword();
    Set<Integer> permissions = authorReq.getPermissions();
    String nodeName = authorReq.getNodeName();
    try {
      switch (authorType) {
        case UpdateUser:
          authorizer.updateUserPassword(userName, newPassword);
          break;
        case CreateUser:
          authorizer.createUser(userName, password);
          break;
        case CreateRole:
          authorizer.createRole(roleName);
          break;
        case DropUser:
          authorizer.deleteUser(userName);
          break;
        case DropRole:
          authorizer.deleteRole(roleName);
          break;
        case GrantRole:
          for (int i : permissions) {
            authorizer.grantPrivilegeToRole(roleName, nodeName, i);
          }
          break;
        case GrantUser:
          for (int i : permissions) {
            authorizer.grantPrivilegeToUser(userName, nodeName, i);
          }
          break;
        case GrantRoleToUser:
          authorizer.grantRoleToUser(roleName, userName);
          break;
        case RevokeUser:
          for (int i : permissions) {
            authorizer.revokePrivilegeFromUser(userName, nodeName, i);
          }
          break;
        case RevokeRole:
          for (int i : permissions) {
            authorizer.revokePrivilegeFromRole(roleName, nodeName, i);
          }
          break;
        case RevokeRoleFromUser:
          authorizer.revokeRoleFromUser(roleName, userName);
          break;
        default:
          throw new AuthException("unknown type: " + authorReq.getAuthorType());
      }
    } catch (AuthException e) {
      return RpcUtils.getStatus(TSStatusCode.EXECUTE_PERMISSION_EXCEPTION_ERROR, e.getMessage());
    }
    return RpcUtils.getStatus(TSStatusCode.SUCCESS_STATUS);
  }

  public PermissionInfoResp executeListRole() {
    PermissionInfoResp result = new PermissionInfoResp();
    List<String> roleList = authorizer.listAllRoles();
    Map<String, List<String>> permissionInfo = new HashMap<>();
    permissionInfo.put(IoTDBConstant.COLUMN_ROLE, roleList);
    result.setStatus(RpcUtils.getStatus(TSStatusCode.SUCCESS_STATUS));
    result.setPermissionInfo(permissionInfo);
    return result;
  }

  public PermissionInfoResp executeListUser() {
    PermissionInfoResp result = new PermissionInfoResp();
    List<String> userList = authorizer.listAllUsers();
    Map<String, List<String>> permissionInfo = new HashMap<>();
    permissionInfo.put(IoTDBConstant.COLUMN_USER, userList);
    result.setStatus(RpcUtils.getStatus(TSStatusCode.SUCCESS_STATUS));
    result.setPermissionInfo(permissionInfo);
    return result;
  }

  public PermissionInfoResp executeListRoleUsers(AuthorReq plan) throws AuthException {
    PermissionInfoResp result = new PermissionInfoResp();
    Map<String, List<String>> permissionInfo = new HashMap<>();
    Role role;
    try {
      role = authorizer.getRole(plan.getRoleName());
      if (role == null) {
        result.setStatus(
            RpcUtils.getStatus(
                TSStatusCode.ROLE_NOT_EXIST_ERROR, "No such role : " + plan.getRoleName()));
        result.setPermissionInfo(permissionInfo);
        return result;
      }
    } catch (AuthException e) {
      throw new AuthException(e);
    }
    List<String> roleUsersList = new ArrayList<>();
    List<String> userList = authorizer.listAllUsers();
    for (String userN : userList) {
      User userObj = authorizer.getUser(userN);
      if (userObj != null && userObj.hasRole(plan.getRoleName())) {
        roleUsersList.add(userN);
      }
    }
    permissionInfo.put(IoTDBConstant.COLUMN_USER, roleUsersList);
    result.setStatus(RpcUtils.getStatus(TSStatusCode.SUCCESS_STATUS));
    result.setPermissionInfo(permissionInfo);
    return result;
  }

  public PermissionInfoResp executeListUserRoles(AuthorReq plan) throws AuthException {
    PermissionInfoResp result = new PermissionInfoResp();
    Map<String, List<String>> permissionInfo = new HashMap<>();
    User user;
    try {
      user = authorizer.getUser(plan.getUserName());
      if (user == null) {
        result.setStatus(
            RpcUtils.getStatus(
                TSStatusCode.USER_NOT_EXIST_ERROR, "No such user : " + plan.getUserName()));
        result.setPermissionInfo(permissionInfo);
        return result;
      }
    } catch (AuthException e) {
      throw new AuthException(e);
    }
    List<String> userRoleList = new ArrayList<>();
    for (String roleN : user.getRoleList()) {
      userRoleList.add(roleN);
    }

    permissionInfo.put(IoTDBConstant.COLUMN_ROLE, userRoleList);
    result.setStatus(RpcUtils.getStatus(TSStatusCode.SUCCESS_STATUS));
    result.setPermissionInfo(permissionInfo);
    return result;
  }

  public PermissionInfoResp executeListRolePrivileges(AuthorReq plan) throws AuthException {
    PermissionInfoResp result = new PermissionInfoResp();
    Map<String, List<String>> permissionInfo = new HashMap<>();
    Role role;
    try {
      role = authorizer.getRole(plan.getRoleName());
      if (role == null) {
        result.setStatus(
            RpcUtils.getStatus(
                TSStatusCode.ROLE_NOT_EXIST_ERROR, "No such role : " + plan.getRoleName()));
        result.setPermissionInfo(permissionInfo);
        return result;
      }
    } catch (AuthException e) {
      throw new AuthException(e);
    }
    List<String> rolePrivilegesList = new ArrayList<>();
    for (PathPrivilege pathPrivilege : role.getPrivilegeList()) {
      if (plan.getNodeName().equals("")
          || AuthUtils.pathBelongsTo(plan.getNodeName(), pathPrivilege.getPath())) {
        rolePrivilegesList.add(pathPrivilege.toString());
      }
    }

    permissionInfo.put(IoTDBConstant.COLUMN_PRIVILEGE, rolePrivilegesList);
    result.setStatus(RpcUtils.getStatus(TSStatusCode.SUCCESS_STATUS));
    result.setPermissionInfo(permissionInfo);
    return result;
  }

  public PermissionInfoResp executeListUserPrivileges(AuthorReq plan) throws AuthException {
    PermissionInfoResp result = new PermissionInfoResp();
    Map<String, List<String>> permissionInfo = new HashMap<>();
    User user;
    try {
      user = authorizer.getUser(plan.getUserName());
      if (user == null) {
        result.setStatus(
            RpcUtils.getStatus(
                TSStatusCode.USER_NOT_EXIST_ERROR, "No such user : " + plan.getUserName()));
        result.setPermissionInfo(permissionInfo);
        return result;
      }
    } catch (AuthException e) {
      throw new AuthException(e);
    }
    List<String> userPrivilegesList = new ArrayList<>();

    if (IoTDBConstant.PATH_ROOT.equals(plan.getUserName())) {
      for (PrivilegeType privilegeType : PrivilegeType.values()) {
        userPrivilegesList.add(privilegeType.toString());
      }
      permissionInfo.put(IoTDBConstant.COLUMN_PRIVILEGE, userPrivilegesList);
      result.setStatus(RpcUtils.getStatus(TSStatusCode.SUCCESS_STATUS));
      result.setPermissionInfo(permissionInfo);
      return result;
    } else {
      List<String> rolePrivileges = new ArrayList<>();
      for (PathPrivilege pathPrivilege : user.getPrivilegeList()) {
        if (plan.getNodeName().equals("")
            || AuthUtils.pathBelongsTo(plan.getNodeName(), pathPrivilege.getPath())) {
          rolePrivileges.add("");
          userPrivilegesList.add(pathPrivilege.toString());
        }
      }
      for (String roleN : user.getRoleList()) {
        Role role = authorizer.getRole(roleN);
        if (roleN == null) {
          continue;
        }
        for (PathPrivilege pathPrivilege : role.getPrivilegeList()) {
          if (plan.getNodeName().equals("")
              || AuthUtils.pathBelongsTo(plan.getNodeName(), pathPrivilege.getPath())) {
            rolePrivileges.add(roleN);
            userPrivilegesList.add(pathPrivilege.toString());
          }
        }
      }
      permissionInfo.put(IoTDBConstant.COLUMN_ROLE, rolePrivileges);
      permissionInfo.put(IoTDBConstant.COLUMN_PRIVILEGE, userPrivilegesList);
      result.setStatus(RpcUtils.getStatus(TSStatusCode.SUCCESS_STATUS));
      result.setPermissionInfo(permissionInfo);
      return result;
    }
  }

  @Override
  public boolean processTakeSnapshot(File snapshotDir) throws TException, IOException {
    return authorizer.processTakeSnapshot(snapshotDir);
  }

  @Override
  public void processLoadSnapshot(File snapshotDir) throws TException, IOException {
    authorizer.processLoadSnapshot(snapshotDir);
  }

  @TestOnly
  public void clear() throws AuthException {
    File userFolder = new File(commonConfig.getUserFolder());
    if (userFolder.exists()) {
      FileUtils.deleteDirectory(userFolder);
    }
    File roleFolder = new File(commonConfig.getRoleFolder());
    if (roleFolder.exists()) {
      FileUtils.deleteDirectory(roleFolder);
    }
    authorizer.reset();
  }
}
