/*
 * Tencent is pleased to support the open source community by making BK-JOB蓝鲸智云作业平台 available.
 *
 * Copyright (C) 2021 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-JOB蓝鲸智云作业平台 is licensed under the MIT License.
 *
 * License for BK-JOB蓝鲸智云作业平台:
 *
 * ---------------------------------------------------
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

export const findUsedVariable = (list) => {
    const variableSet = new Set();
    // eslint-disable-next-line no-plusplus
    for (let i = 0; i < list.length; i++) {
        const step = list[i];
        if (step.isScript) {
            // 执行脚本步骤
            const { executeTarget, scriptParam } = step.scriptStepInfo;
            // 1，脚本执行的执行目标使用全局变量
            if (executeTarget.variable) {
                variableSet.add(executeTarget.variable);
            }
            // 2，脚本执行的脚本参数使用全局变量
            const patt = /\${([^}]+)}/g;
            let scriptParamMatch;
            while ((scriptParamMatch = patt.exec(scriptParam)) !== null) {
                variableSet.add(scriptParamMatch[1]);
            }
            continue;
        }
        if (step.isFile) {
            // 分发文件步骤
            const { fileDestination, fileSourceList, destinationFileLocation } = step.fileStepInfo;
            // 检测目标服务器
            const { path, server } = fileDestination;
            if (server.variable) {
                variableSet.add(server.variable);
            }
            // 检测目标路径
            if (path) {
                const patt = /\${([^}]+)}/g;
                let destinationFileLocationMatch;
                while ((destinationFileLocationMatch = patt.exec(destinationFileLocation)) !== null) {
                    variableSet.add(destinationFileLocationMatch[1]);
                }
            }
            // 检测服务器源文件
            // eslint-disable-next-line no-plusplus
            for (let j = 0; j < fileSourceList.length; j++) {
                const currentFile = fileSourceList[j];
                if (currentFile.fileType === 1) {
                    // 服务器文件_服务器列表
                    if (currentFile.host.variable) {
                        variableSet.add(currentFile.host.variable);
                    }
                    // 服务器文件_来源路径
                    currentFile.fileLocation.forEach((fileLocation) => {
                        const patt = /\${([^}]+)}/g;
                        let fileLocationMatch;
                        while ((fileLocationMatch = patt.exec(fileLocation)) !== null) {
                            variableSet.add(fileLocationMatch[1]);
                        }
                    });
                }
            }
            
            continue;
        }
    }
    return [
        ...variableSet,
    ];
};

export const isPublicScript = (route) => {
    const { meta } = route;
    if (!meta) {
        return false;
    }
    if (meta.public) {
        return true;
    }
    return false;
};

export const checkPublicScript = (route) => {
    const { meta } = route;
    if (!meta) {
        return false;
    }
    if (meta.public) {
        return true;
    }
    return false;
};

export const compareHost = (preHost, nextHost) => {
    // 全都使用了主机变量
    if (nextHost.variable && nextHost.variable === preHost.variable) {
        return true;
    }
    // 服务文件主机手动添加
    // 目标服务器主机使用主机变量
    if (nextHost.variable) {
        return false;
    }
    // 全都手动添加对比值
    const {
        ipList: preIPList,
        topoNodeList: preNodeList,
        dynamicGroupList: preGroupList,
    } = preHost.hostNodeInfo;
    const {
        ipList: nextIPList,
        topoNodeList: nextNodeList,
        dynamicGroupList: nextGroupList,
    } = nextHost.hostNodeInfo;
    // 对比主机
    if (preIPList.length !== nextIPList.length) {
        return false;
    }
    const genHostKey = host => `${host.cloudAreaInfo.id}:${host.ip}`;
    const preIPMap = preIPList.reduce((result, host) => {
        result[genHostKey(host)] = true;
        return result;
    }, {});
    // eslint-disable-next-line no-plusplus
    for (let i = 0; i < nextIPList.length; i++) {
        if (!preIPMap[genHostKey(nextIPList[i])]) {
            return false;
        }
    }
    // 对比节点
    if (preNodeList.length !== nextNodeList.length) {
        return false;
    }
    const genNodeKey = node => `#${node.id}#${node.type}`;
    const taretNodeMap = preNodeList.reduce((result, node) => {
        result[genNodeKey(node)] = true;
        return result;
    }, {});
    // eslint-disable-next-line no-plusplus
    for (let i = 0; i < nextNodeList.length; i++) {
        if (!taretNodeMap[genNodeKey(nextNodeList[i])]) {
            return false;
        }
    }
    // 对比分组
    if (preGroupList.length !== nextGroupList.length) {
        return false;
    }
    const preGroupMap = preGroupList.reduce((result, groupId) => {
        result[groupId] = true;
        return result;
    }, {});
    // eslint-disable-next-line no-plusplus
    for (let i = 0; i < nextGroupList.length; i++) {
        if (!preGroupMap[nextGroupList[i]]) {
            return false;
        }
    }
    return true;
};

export const detectionSourceFileDupLocation = (fileSourceList) => {
    const fileLocationMap = {};
    const pathReg = /([^/]+\/?)\*?$/;
    // 路径中以 * 结尾表示分发所有文件，可能和分发具体文件冲突
    let hasDirAllFile = false;
    let hasFile = false;
    // eslint-disable-next-line no-plusplus
    for (let i = 0; i < fileSourceList.length; i++) {
        const currentFileSource = fileSourceList[i];
        // eslint-disable-next-line no-plusplus
        for (let j = 0; j < currentFileSource.fileLocation.length; j++) {
            const currentFileLocation = currentFileSource.fileLocation[j];
            // 分发所有文件
            if (/\*$/.test(currentFileLocation)) {
                hasDirAllFile = true;
                if (hasFile) {
                    return true;
                }
                continue;
            }
            // 分发具体的文件
            if (!/(\/|(\/\*))$/.test(currentFileLocation)) {
                hasFile = true;
                if (hasDirAllFile) {
                    return true;
                }
            }
            const pathMatch = currentFileLocation.match(pathReg);
            if (pathMatch) {
                if (fileLocationMap[pathMatch[1]]) {
                    return true;
                }
                fileLocationMap[pathMatch[1]] = 1;
            }
        }
    }
    return false;
};
