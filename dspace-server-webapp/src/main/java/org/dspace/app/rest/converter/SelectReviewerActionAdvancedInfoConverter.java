/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.converter;

import org.dspace.app.rest.model.SelectReviewerActionAdvancedInfoRest;
import org.dspace.app.rest.projection.Projection;
import org.dspace.xmlworkflow.state.actions.processingaction.SelectReviewerActionAdvancedInfo;

public class SelectReviewerActionAdvancedInfoConverter
    implements DSpaceConverter<SelectReviewerActionAdvancedInfo, SelectReviewerActionAdvancedInfoRest> {

    @Override
    public SelectReviewerActionAdvancedInfoRest convert(SelectReviewerActionAdvancedInfo modelObject,
                                                        Projection projection) {
        SelectReviewerActionAdvancedInfoRest restModel = new SelectReviewerActionAdvancedInfoRest();
        restModel.setProjection(projection);
        restModel.setRole(modelObject.getRole());
        restModel.setType(modelObject.getType());
        restModel.setId(modelObject.getId());
        return restModel;
    }

    @Override
    public Class<SelectReviewerActionAdvancedInfo> getModelClass() {
        return SelectReviewerActionAdvancedInfo.class;
    }
}
