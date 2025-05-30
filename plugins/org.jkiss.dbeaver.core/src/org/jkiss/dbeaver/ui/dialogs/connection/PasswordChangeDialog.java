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
package org.jkiss.dbeaver.ui.dialogs.connection;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.jkiss.dbeaver.core.CoreMessages;
import org.jkiss.dbeaver.model.DBIcon;
import org.jkiss.dbeaver.model.access.DBAPasswordChangeInfo;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.dialogs.BaseDialog;
import org.jkiss.utils.CommonUtils;

/**
 * Password change dialog
 */
public class PasswordChangeDialog extends BaseDialog
{
    private DBAPasswordChangeInfo passwordInfo;
    private String verifyText;
    private boolean userEditable;
    private boolean oldPasswordVisible;

    public PasswordChangeDialog(Shell parentShell, String title, String userName, String oldPassword, boolean userEditable, boolean oldPasswordVisible)
    {
        super(parentShell, title, DBIcon.TREE_USER);
        this.passwordInfo = new DBAPasswordChangeInfo(userName, oldPassword);
        this.userEditable = userEditable;
        this.oldPasswordVisible = oldPasswordVisible;
    }

    public DBAPasswordChangeInfo getPasswordInfo()
    {
        return passwordInfo;
    }

    @Override
    protected Composite createDialogArea(Composite parent)
    {
        Composite credGroup = super.createDialogArea(parent);
        ((GridLayout)credGroup.getLayout()).numColumns = 2;

        Control infoLabel = UIUtils.createInfoLabel(credGroup, getTitle());
        GridData gd = new GridData(GridData.FILL_HORIZONTAL);
        gd.horizontalSpan = 2;
        gd.widthHint = 300;
        infoLabel.setLayoutData(gd);

        Text userNameText = UIUtils.createLabelText(credGroup, CoreMessages.dialog_password_change_username, passwordInfo.getUserName(), SWT.BORDER);
        userNameText.addModifyListener(e -> passwordInfo.setUserName(userNameText.getText()));
        userNameText.setEditable(userEditable);
        if (oldPasswordVisible) {
            Text oldPasswordText = UIUtils.createLabelText(credGroup, CoreMessages.dialog_password_change_old_password, passwordInfo.getOldPassword(), SWT.BORDER | SWT.PASSWORD);
            oldPasswordText.addModifyListener(e -> passwordInfo.setOldPassword(oldPasswordText.getText()));
        }
        Text newPasswordText = UIUtils.createLabelText(credGroup, CoreMessages.dialog_password_change_new_password, "", SWT.BORDER | SWT.PASSWORD);
        newPasswordText.addModifyListener(e -> {
            passwordInfo.setNewPassword(newPasswordText.getText());
            updateButtons();
        });
        if (!userEditable) {
            newPasswordText.setFocus();
        }
        Text verifyPasswordText = UIUtils.createLabelText(credGroup, CoreMessages.dialog_password_change_verify_password, "", SWT.BORDER | SWT.PASSWORD);
        verifyPasswordText.addModifyListener(e -> {
            verifyText = verifyPasswordText.getText();
            updateButtons();
        });

        return credGroup;
    }

    private void updateButtons() {
        getButton(IDialogConstants.OK_ID).setEnabled(
            !CommonUtils.isEmpty(passwordInfo.getUserName()) &&
            !CommonUtils.isEmpty(passwordInfo.getNewPassword()) &&
            CommonUtils.equalObjects(passwordInfo.getNewPassword(), verifyText));
    }

}
