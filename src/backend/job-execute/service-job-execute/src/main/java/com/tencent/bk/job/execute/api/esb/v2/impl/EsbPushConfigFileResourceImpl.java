/*
 * Tencent is pleased to support the open source community by making BK-JOB蓝鲸智云作业平台 available.
 *
 * Copyright (C) 2021 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-JOB蓝鲸智云作业平台 is licensed under the MIT License.
 *
 * License for BK-JOB蓝鲸智云作业平台:
 * --------------------------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and
 * to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
 * THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
 * CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
 * IN THE SOFTWARE.
 */

package com.tencent.bk.job.execute.api.esb.v2.impl;

import com.tencent.bk.job.common.constant.ErrorCode;
import com.tencent.bk.job.common.esb.metrics.EsbApiTimed;
import com.tencent.bk.job.common.esb.model.EsbResp;
import com.tencent.bk.job.common.exception.ServiceException;
import com.tencent.bk.job.common.i18n.MessageI18nService;
import com.tencent.bk.job.common.iam.exception.InSufficientPermissionException;
import com.tencent.bk.job.common.iam.service.AuthService;
import com.tencent.bk.job.common.model.ValidateResult;
import com.tencent.bk.job.common.util.Base64Util;
import com.tencent.bk.job.common.util.JobUUID;
import com.tencent.bk.job.common.util.date.DateUtils;
import com.tencent.bk.job.execute.api.esb.v2.EsbPushConfigFileResource;
import com.tencent.bk.job.execute.common.constants.RunStatusEnum;
import com.tencent.bk.job.execute.common.constants.StepExecuteTypeEnum;
import com.tencent.bk.job.execute.common.constants.TaskStartupModeEnum;
import com.tencent.bk.job.execute.common.constants.TaskTypeEnum;
import com.tencent.bk.job.execute.config.StorageSystemConfig;
import com.tencent.bk.job.execute.engine.consts.FileDirTypeConf;
import com.tencent.bk.job.execute.engine.util.FileUtils;
import com.tencent.bk.job.execute.engine.util.NFSUtils;
import com.tencent.bk.job.execute.model.*;
import com.tencent.bk.job.execute.model.esb.v2.EsbJobExecuteDTO;
import com.tencent.bk.job.execute.model.esb.v2.request.EsbPushConfigFileRequest;
import com.tencent.bk.job.execute.service.AccountService;
import com.tencent.bk.job.execute.service.TaskExecuteService;
import com.tencent.bk.job.manage.common.consts.account.AccountCategoryEnum;
import com.tencent.bk.job.manage.common.consts.task.TaskFileTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

@RestController
@Slf4j
public class EsbPushConfigFileResourceImpl extends JobExecuteCommonProcessor implements EsbPushConfigFileResource {
    private final TaskExecuteService taskExecuteService;

    private final MessageI18nService i18nService;

    private final AccountService accountService;

    private final StorageSystemConfig storageSystemConfig;

    private final AuthService authService;

    @Autowired
    public EsbPushConfigFileResourceImpl(TaskExecuteService taskExecuteService, MessageI18nService i18nService,
                                         AccountService accountService, StorageSystemConfig storageSystemConfig,
                                         AuthService authService) {
        this.taskExecuteService = taskExecuteService;
        this.i18nService = i18nService;
        this.accountService = accountService;
        this.storageSystemConfig = storageSystemConfig;
        this.authService = authService;
    }

    @Override
    @EsbApiTimed(value = "esb.api", extraTags = {"api_name", "v2_push_config_file"})
    public EsbResp<EsbJobExecuteDTO> pushConfigFile(String lang, EsbPushConfigFileRequest request) {
        ValidateResult checkResult = checkPushConfigFileRequest(request);
        if (!checkResult.isPass()) {
            log.warn("Fast transfer file request is illegal!");
            return EsbResp.buildCommonFailResp(i18nService, checkResult);
        }

        request.trimIps();

        String uploadPath = NFSUtils.getFileDir(storageSystemConfig.getJobStorageRootPath(),
            FileDirTypeConf.UPLOAD_FILE_DIR);

        List<String> localFileList = new ArrayList<>();
        for (EsbPushConfigFileRequest.EsbConfigFileDTO configFile : request.getFileList()) {
            String fileName = configFile.getFileName();
            String base64EncodeContent = configFile.getContent();
            byte[] contentBytes = Base64Util.decodeContentToByte(base64EncodeContent);
            String localFileRelativePath = JobUUID.getUUID() + File.separatorChar +
                request.getUserName() + File.separatorChar + fileName;
            String fullFilePath = uploadPath.concat(localFileRelativePath);
            File theFile = new File(fullFilePath);
            File parentDir = theFile.getParentFile();
            if (!parentDir.exists()) {
                if (!parentDir.mkdirs()) {
                    log.warn("Push config file, mkdir parent dir fail!dir:{}", parentDir.getAbsolutePath());
                    return EsbResp.buildCommonFailResp(ErrorCode.STARTUP_TASK_FAIL, i18nService);
                }
                if (!parentDir.setWritable(true, false)) {
                    log.warn("Push config file, set parent dir writeable fail!dir:{}", parentDir.getAbsolutePath());
                    return EsbResp.buildCommonFailResp(ErrorCode.STARTUP_TASK_FAIL, i18nService);
                }
            }
            if (theFile.exists() && theFile.isFile()) {
                if (!theFile.delete()) {
                    log.warn("Push config file, delete old file fail!dir:{}", theFile.getAbsolutePath());
                    return EsbResp.buildCommonFailResp(ErrorCode.STARTUP_TASK_FAIL, i18nService);
                }
            }

            if (!FileUtils.saveFileWithByte(fullFilePath, contentBytes)) {
                log.warn("Push config file, save file failed!fileName:{}", theFile.getAbsolutePath());
                return EsbResp.buildCommonFailResp(ErrorCode.STARTUP_TASK_FAIL, i18nService);
            }
            localFileList.add(localFileRelativePath);
        }

        try {
            TaskInstanceDTO taskInstance = buildFastFileTaskInstance(request);
            StepInstanceDTO stepInstance = buildFastFileStepInstance(request, localFileList);
            long taskInstanceId = taskExecuteService.createTaskInstanceFast(taskInstance, stepInstance);
            taskExecuteService.startTask(taskInstanceId);

            EsbJobExecuteDTO jobExecuteInfo = new EsbJobExecuteDTO();
            jobExecuteInfo.setTaskInstanceId(taskInstanceId);
            jobExecuteInfo.setTaskName(stepInstance.getName());
            return EsbResp.buildSuccessResp(jobExecuteInfo);
        } catch (InSufficientPermissionException e) {
            return authService.buildEsbAuthFailResp(e);
        } catch (ServiceException e) {
            log.warn("Fail to start task", e);
            return EsbResp.buildCommonFailResp(e, i18nService);
        } catch (Exception e) {
            log.warn("Fail to start task", e);
            return EsbResp.buildCommonFailResp(ErrorCode.STARTUP_TASK_FAIL, i18nService);
        }
    }

    private TaskInstanceDTO buildFastFileTaskInstance(EsbPushConfigFileRequest request) {
        TaskInstanceDTO taskInstance = new TaskInstanceDTO();
        taskInstance.setType(TaskTypeEnum.FILE.getValue());
        if (StringUtils.isNotEmpty(request.getName())) {
            taskInstance.setName(request.getName());
        } else {
            taskInstance.setName("API_PUSH_CONFIG_FILE_" + DateUtils.currentTimeMillis());
        }
        taskInstance.setTaskId(-1L);
        taskInstance.setCronTaskId(-1L);
        taskInstance.setTaskTemplateId(-1L);
        taskInstance.setAppId(request.getAppId());
        taskInstance.setStatus(RunStatusEnum.BLANK.getValue());
        taskInstance.setStartupMode(TaskStartupModeEnum.API.getValue());
        taskInstance.setOperator(request.getUserName());
        taskInstance.setCreateTime(DateUtils.currentTimeMillis());
        taskInstance.setCurrentStepId(0L);
        taskInstance.setDebugTask(false);
        taskInstance.setAppCode(request.getAppCode());
        return taskInstance;
    }

    private StepInstanceDTO buildFastFileStepInstance(EsbPushConfigFileRequest request, List<String> localFileList) {
        StepInstanceDTO stepInstance = new StepInstanceDTO();
        if (StringUtils.isNotEmpty(request.getName())) {
            stepInstance.setName(request.getName());
        } else {
            stepInstance.setName("API_PUSH_CONFIG_FILE_" + DateUtils.currentTimeMillis());
        }
        AccountDTO account = checkAndGetOsAccount(request.getAppId(), request.getAccount());
        stepInstance.setAccountId(account.getId());
        stepInstance.setAccount(account.getAccount());
        stepInstance.setStepId(-1L);
        stepInstance.setExecuteType(StepExecuteTypeEnum.SEND_FILE.getValue());
        stepInstance.setFileTargetPath(request.getTargetPath());
        stepInstance.setFileSourceList(convertFileSource(localFileList));
        stepInstance.setAppId(request.getAppId());
        stepInstance.setTargetServers(convertToStandardServers(null, request.getIpList(), null));
        stepInstance.setOperator(request.getUserName());
        stepInstance.setStatus(RunStatusEnum.BLANK.getValue());
        stepInstance.setCreateTime(DateUtils.currentTimeMillis());
        return stepInstance;
    }

    private AccountDTO checkAndGetOsAccount(long appId, String accountAlias) throws ServiceException {
        AccountDTO account = accountService.getSystemAccountByAlias(accountAlias, appId);
        if (account == null) {
            log.info("Account:{} is not exist in app:{}", accountAlias, appId);
            throw new ServiceException(ErrorCode.ACCOUNT_NOT_EXIST, accountAlias);
        }
        if (AccountCategoryEnum.SYSTEM != account.getCategory()) {
            log.info("Account:{} is not os account in app:{}", accountAlias, appId);
            throw new ServiceException(ErrorCode.ACCOUNT_NOT_EXIST, accountAlias);
        }
        return account;
    }

    private List<FileSourceDTO> convertFileSource(List<String> configFiles) throws ServiceException {
        if (configFiles == null) {
            return null;
        }
        List<FileSourceDTO> fileSourceDTOS = new ArrayList<>();
        configFiles.forEach(configFile -> {
            FileSourceDTO fileSourceDTO = new FileSourceDTO();
            fileSourceDTO.setAccount("root");
            fileSourceDTO.setLocalUpload(true);
            fileSourceDTO.setFileType(TaskFileTypeEnum.LOCAL.getType());
            List<FileDetailDTO> files = new ArrayList<>();
            files.add(new FileDetailDTO(configFile));
            fileSourceDTO.setFiles(files);
            fileSourceDTOS.add(fileSourceDTO);
        });
        return fileSourceDTOS;
    }

    private ValidateResult checkPushConfigFileRequest(EsbPushConfigFileRequest request) {
        if (request.getAppId() == null || request.getAppId() < 1) {
            log.warn("Push config file, appId is empty or invalid!");
            return ValidateResult.fail(ErrorCode.MISSING_OR_ILLEGAL_PARAM_WITH_PARAM_NAME, "bk_biz_id");
        }
        if (StringUtils.isBlank(request.getTargetPath())) {
            log.warn("Push config file, targetPath is empty!");
            return ValidateResult.fail(ErrorCode.MISSING_OR_ILLEGAL_PARAM_WITH_PARAM_NAME, "file_target_path");
        }
        if (StringUtils.isBlank(request.getAccount())) {
            log.warn("Push config file, account is empty!");
            return ValidateResult.fail(ErrorCode.MISSING_OR_ILLEGAL_PARAM_WITH_PARAM_NAME, "account");
        }
        if (request.getIpList() == null || request.getIpList().isEmpty()) {
            log.warn("Push config file, ipList is empty!");
            return ValidateResult.fail(ErrorCode.MISSING_OR_ILLEGAL_PARAM_WITH_PARAM_NAME, "ip_list");
        }
        if (request.getFileList() == null || request.getFileList().isEmpty()) {
            log.warn("Push config file, fileList is empty!");
            return ValidateResult.fail(ErrorCode.MISSING_OR_ILLEGAL_PARAM_WITH_PARAM_NAME, "file_list");
        }

        List<EsbPushConfigFileRequest.EsbConfigFileDTO> fileList = request.getFileList();
        for (EsbPushConfigFileRequest.EsbConfigFileDTO fileInfo : fileList) {
            if (StringUtils.isBlank(fileInfo.getFileName())) {
                log.warn("Push config file, empty file name!");
                return ValidateResult.fail(ErrorCode.ILLEGAL_PARAM_WITH_PARAM_NAME, "file_list.file_name");
            }
            if (StringUtils.isBlank(fileInfo.getContent())) {
                log.warn("Push config file, config file content is empty!");
                return ValidateResult.fail(ErrorCode.ILLEGAL_PARAM_WITH_PARAM_NAME, "file_list.content");
            }
        }
        return ValidateResult.pass();
    }

}