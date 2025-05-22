/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jkiss.dbeaver.ext.oracle.internal;

import org.jkiss.dbeaver.utils.NLS;

public class OracleMessages extends NLS {
    static final String BUNDLE_NAME = "org.jkiss.dbeaver.ext.oracle.internal.OracleMessages"; //$NON-NLS-1$

    static {
        // initialize resource bundle
        NLS.initializeMessages(BUNDLE_NAME, OracleMessages.class);
    }

    private OracleMessages() {
    }

    public static String dialog_connection_sid;
    public static String dialog_connection_service;
    public static String edit_oracle_dependencies_dependency_name;
    public static String edit_oracle_dependencies_dependency_description;
    public static String edit_oracle_dependencies_dependent_name;
    public static String edit_oracle_dependencies_dependent_description;
    public static String oracle_password_will_expire_warn_name;
    public static String oracle_password_will_expire_warn_description;
    public static String oracle_server_session_manager_details_name;
    public static String oracle_server_session_manager_details_description;
    public static String oracle_server_session_manager_display_exec_plan_name;
    public static String oracle_server_session_manager_display_exec_plan_description;
    public static String pseudo_column_rowid_description;
    public static String pseudo_column_ora_rowscn_description;
    public static String global_variable_sysdate;
    public static String global_variable_systimestamp;
    public static String global_variable_dbtimezone;
    public static String global_variable_sessiontimezone;
    public static String global_variable_current_timestamp;
    public static String global_variable_current_date;
    public static String global_variable_ora_invoking_user;
    public static String global_variable_ora_invoking_userid;
    public static String global_variable_uid;
    public static String global_variable_user;
}
