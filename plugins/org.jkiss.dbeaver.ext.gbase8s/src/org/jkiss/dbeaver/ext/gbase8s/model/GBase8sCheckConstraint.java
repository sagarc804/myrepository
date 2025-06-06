/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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

package org.jkiss.dbeaver.ext.gbase8s.model;

import org.jkiss.dbeaver.ext.generic.model.GenericTableBase;
import org.jkiss.dbeaver.model.meta.Property;
import org.jkiss.dbeaver.model.struct.DBSEntityConstraintType;
import org.jkiss.dbeaver.model.struct.rdb.DBSTableCheckConstraint;

/**
 * @author Chao Tian
 */
public class GBase8sCheckConstraint extends GBase8sUniqueKey implements DBSTableCheckConstraint {

    private String condition;

    public GBase8sCheckConstraint(GenericTableBase table, String name, String remarks,
            DBSEntityConstraintType constraintType, String condition, boolean persisted) {
        super(table, name, remarks, constraintType, persisted);
        this.condition = condition;
    }

    @Property(viewable = true, order = 10)
    @Override
    public String getCheckConstraintDefinition() {
        return condition;
    }

    @Override
    public void setCheckConstraintDefinition(
            String expression) {
        this.condition = expression;
    }
}
