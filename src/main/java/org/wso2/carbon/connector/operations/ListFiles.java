/*
 * Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.connector.operations;

import org.apache.axiom.om.OMAttribute;
import org.apache.axiom.om.OMElement;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.vfs2.FileFilter;
import org.apache.commons.vfs2.FileFilterSelector;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.synapse.MessageContext;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.carbon.connector.core.AbstractConnector;
import org.wso2.carbon.connector.core.ConnectException;
import org.wso2.carbon.connector.core.connection.ConnectionHandler;
import org.wso2.carbon.connector.core.util.ConnectorUtils;
import org.wso2.carbon.connector.exception.FileOperationException;
import org.wso2.carbon.connector.exception.IllegalPathException;
import org.wso2.carbon.connector.exception.InvalidConfigurationException;
import org.wso2.carbon.connector.pojo.FileOperationResult;
import org.wso2.carbon.connector.pojo.FileSorter;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.carbon.connector.utils.Const;
import org.wso2.carbon.connector.utils.Utils;
import org.wso2.carbon.connector.utils.SimpleFileFiler;

import java.util.ArrayList;
import java.util.Arrays;


/**
 * Implements File listing capability
 * in a directory.
 */
public class ListFiles extends AbstractConnector {

    private static final String MATCHING_PATTERN = "matchingPattern";
    private static final String RECURSIVE_PARAM = "recursive";
    private static final String SORT_ATTRIB_PARAM = "sortingAttribute";
    private static final String SORT_ORDER_PARAM = "sortingOrder";
    private static final String DEFAULT_SORT_ATTRIB = "Name";
    private static final String DEFAULT_SORT_ORDER = "Ascending";
    private static final String DIRECTORY_ELE_NAME = "directory";
    private static final String NAME_ATTRIBUTE = "name";

    private static final String OPERATION_NAME = "listFiles";
    private static final String ERROR_MESSAGE = "Error while performing file:listFiles for folder ";

    @Override
    public void connect(MessageContext messageContext) throws ConnectException {

        ConnectionHandler handler = ConnectionHandler.getConnectionHandler();
        String folderPath = null;
        String fileMatchingPattern;
        boolean recursive;
        FileObject folder = null;

        try {

            //get connection
            String connectionName = Utils.getConnectionName(messageContext);
            FileSystemHandler fileSystemHandler = (FileSystemHandler) handler
                    .getConnection(Const.CONNECTOR_NAME, connectionName);
            FileSystemManager fsManager = fileSystemHandler.getFsManager();
            FileSystemOptions fso = fileSystemHandler.getFsOptions();

            //read inputs
            folderPath = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, Const.DIRECTORY_PATH);
            fileMatchingPattern = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, MATCHING_PATTERN);
            if (StringUtils.isEmpty(fileMatchingPattern)) {
                fileMatchingPattern = Const.MATCH_ALL_REGEX;
            }
            String recursiveStr = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, RECURSIVE_PARAM);
            recursive = Boolean.parseBoolean(recursiveStr);

            String sortingAttribute = Utils.lookUpStringParam(messageContext, SORT_ATTRIB_PARAM, DEFAULT_SORT_ATTRIB);
            String sortingOrder  = Utils.lookUpStringParam(messageContext, SORT_ORDER_PARAM, DEFAULT_SORT_ORDER);

            folderPath = fileSystemHandler.getBaseDirectoryPath() + folderPath;
            folder = fsManager.resolveFile(folderPath, fso);

            if (folder.exists()) {

                if (folder.isFolder()) {

                    OMElement fileListEle = listFilesInFolder(folder, fileMatchingPattern,
                            recursive, sortingAttribute, sortingOrder);
                    FileOperationResult result = new FileOperationResult(
                            OPERATION_NAME,
                            true,
                            fileListEle);
                    Utils.setResultAsPayload(messageContext, result);

                } else {
                    throw new FileOperationException("Folder is expected.");
                }

            } else {
                throw new IllegalPathException("Folder does not exist.");
            }

        } catch (InvalidConfigurationException e) {

            String errorDetail = ERROR_MESSAGE + folderPath;
            handleError(messageContext, e, Error.INVALID_CONFIGURATION, errorDetail);

        } catch (FileSystemException e) {

            String errorDetail = ERROR_MESSAGE + folderPath;
            handleError(messageContext, e, Error.OPERATION_ERROR, errorDetail);

        } catch (IllegalPathException e) {

            String errorDetail = ERROR_MESSAGE + folderPath;
            handleError(messageContext, e, Error.ILLEGAL_PATH, errorDetail);
        } finally {

            if (folder != null) {
                try {
                    folder.close();
                } catch (FileSystemException e) {
                    log.error(Const.CONNECTOR_NAME
                            + ":Error while closing file object while creating directory "
                            + folder);
                }
            }
        }
    }

    /**
     * List all files in the directory. If recursive = true,
     * This method will recursively look into subdirectories.
     * Lists files adhering to sort attribute and order specified.
     *
     * @param folder    Folder to scan
     * @param pattern   Specific pattern of files to include in the listing
     * @param recursive true, if to look into subdirectories
     * @param sortingAttribute Attribute to use for file sorting
     * @param sortOrder Sorting order to use
     * @return OMElement with organized listing.
     * @throws FileSystemException In case of reading the directory.
     */
    private OMElement listFilesInFolder(FileObject folder, String pattern, boolean recursive,
                                        String sortingAttribute, String sortOrder) throws FileSystemException {
        String containingFolderName = folder.getName().getBaseName();
        OMElement folderEle = Utils.createOMElement(DIRECTORY_ELE_NAME, null);
        folderEle.addAttribute(NAME_ATTRIBUTE, containingFolderName, null);
        FileObject[] filesOrFolders = getFilesAndFolders(folder, pattern);
        FileSorter fileSorter = new FileSorter(sortingAttribute, sortOrder);
        fileSorter.sort(filesOrFolders);
        for (FileObject fileOrFolder : filesOrFolders) {
            if (fileOrFolder.isFile()) {
                OMElement fileEle = Utils.createOMElement(Const.FILE_ELEMENT,
                        fileOrFolder.getName().getBaseName());
                folderEle.addChild(fileEle);
            } else {
                if (recursive) {
                    OMElement subFolderEle = listFilesInFolder(fileOrFolder, pattern, recursive,
                            sortingAttribute, sortOrder);
                    folderEle.addChild(subFolderEle);
                }
            }
        }
        return folderEle;
    }

    /**
     * Finds the set of matching descendants of this file.
     *
     * @param pattern pattern to match
     */
    private FileObject[] getFilesAndFolders(FileObject folder, String pattern) throws FileSystemException {

        FileFilter fileFilter = new SimpleFileFiler(pattern);
        FileFilterSelector fileFilterSelector = new FileFilterSelector(fileFilter);
        ArrayList<FileObject> matchingFilesAndFolders =
                new ArrayList<>(Arrays.asList(folder.findFiles(fileFilterSelector)));
        //when a pattern exists folder.findFiles does not return folders
        if (!StringUtils.isEmpty(pattern)) {
            FileObject[] children = folder.getChildren();
            for (FileObject child : children) {
                if (child.isFolder()) {
                    matchingFilesAndFolders.add(child);
                }
            }
        }
        FileObject[] filesWithFolders = new FileObject[matchingFilesAndFolders.size()];
        return matchingFilesAndFolders.toArray(filesWithFolders);
    }

    /**
     * Sets error to context and handle.
     *
     * @param msgCtx      Message Context to set info
     * @param e           Exception associated
     * @param error       Error code
     * @param errorDetail Error detail
     */
    private void handleError(MessageContext msgCtx, Exception e, Error error, String errorDetail) {
        Utils.setError(OPERATION_NAME, msgCtx, e, error, errorDetail);
        handleException(errorDetail, e, msgCtx);
    }
}
