package ca.uhn.fhir.jpa.empi;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.hl7.fhir.dstu3.model.HumanName;
import org.hl7.fhir.dstu3.model.Identifier;
import org.hl7.fhir.dstu3.model.Patient;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import ca.uhn.fhir.jpa.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.dao.IFhirResourceDaoPatient;
import ca.uhn.fhir.jpa.dao.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.DateParam;
import ca.uhn.fhir.rest.param.StringParam;
import ca.uhn.fhir.rest.server.interceptor.ServerOperationInterceptorAdapter;

/*
 * #%L
 * HAPI FHIR JPA Server
 * %%
 * Copyright (C) 2014 - 2018 University Health Network
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
public class PatientMatchingInterceptorDstu3 extends ServerOperationInterceptorAdapter {

    private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(PatientMatchingInterceptorDstu3.class);

    @Autowired
    @Qualifier("myPatientDaoDstu3")
    private IFhirResourceDao<Patient> myPatientDao;

    private String myMasterPatientIndexSystem;

    public PatientMatchingInterceptorDstu3(String theMasterPatientIndexSystem) {
        super();

        Validate.notBlank(theMasterPatientIndexSystem);
        ourLog.info("EMPI system {}.", myMasterPatientIndexSystem);
        this.myMasterPatientIndexSystem = theMasterPatientIndexSystem;
    }

    public void setDao(IFhirResourceDaoPatient<Patient> theDao) {
        myPatientDao = theDao;
    }

    @Override
    public void resourcePreCreate(RequestDetails theRequest, IBaseResource theResource) {

        if (!myPatientDao.getContext().getResourceDefinition(theResource).getName().equals("Patient"))
            return; // skip if it's not patient

        Patient thePatient = (Patient) theResource;

        // 1. get matched patient resources
        List<IBaseResource> theOtherPatientList = getMatchedPatient(theRequest, thePatient);

        // 2. inject the empi
        injectMasterPatientIndex(thePatient, getMasterPatientIndex(theOtherPatientList));
    }

    @Override
    public void resourcePreUpdate(RequestDetails theRequest, IBaseResource theOldResource, IBaseResource theNewResource) {

        if (!myPatientDao.getContext().getResourceDefinition(theNewResource).getName().equals("Patient"))
            return; // skip if it's not patient

        // 1. if the new resource has empi, do nothing
        Patient theNewPatient = (Patient) theNewResource;
        if (getMasterPatientIndex(theNewPatient) != null)
            return;

        // 2. otherwise, copy the empi from old resource
        Patient theOldPatient = (Patient) theOldResource;
        String theOldEmpi = getMasterPatientIndex(theOldPatient);
        if (theOldEmpi != null) {
            // found theOldEmpi, inject the old empi to the new patient
            injectMasterPatientIndex(theNewPatient, theOldEmpi);
            return;
        }

        // 3. create new empi if there is no empi from old resource
        List<IBaseResource> theOtherPatientList = getMatchedPatient(theRequest, theNewPatient);
        injectMasterPatientIndex(theNewPatient, getMasterPatientIndex(theOtherPatientList));

    }

    // Looking EMPI from the matched patient,
    // if EMPI are same, we inject the empi id to the new Patient
    // otherwise, generate the new EMPI for the new Patient
    private void injectMasterPatientIndex(Patient thePatient, String empi) {

        Identifier empiIdentifier = new Identifier();
        empiIdentifier.setSystem(myMasterPatientIndexSystem);
        if (empi != null)
            empiIdentifier.setValue(empi);
        else
            empiIdentifier.setValue(UUID.randomUUID().toString());

        thePatient.addIdentifier(empiIdentifier);
    }

    // -- get EMPI from the other patient, return null if there are different
    private String getMasterPatientIndex(List<IBaseResource> theOtherPatientList) {

        String empi = null;
        String currEmpi;

        for (IBaseResource resource : theOtherPatientList) {

            if (!(resource instanceof Patient))
                continue;

            currEmpi = getMasterPatientIndex((Patient) resource);
            if (currEmpi == null)
                continue; // skip the invalid empi

            if (empi == null) { // first one
                empi = currEmpi;
                continue;
            }

            if (!empi.equals(currEmpi)) {
                // something wrong, the empi does not match
                empi = null;
                break;
            }
        }

        return empi;
    }

    private String getMasterPatientIndex(Patient thePatient) {

        List<Identifier> identifierList = thePatient.getIdentifier();
        String empi;
        for (Identifier identifier : identifierList) {

            if (!myMasterPatientIndexSystem.equals(identifier.getSystem()))
                continue;

            empi = identifier.getValue();
            if (empi == null)
                continue;

            return empi;
        }

        return null;
    }

    // TODO need to make it configurable
    private List<IBaseResource> getMatchedPatient(RequestDetails theRequest, Patient thePatient) {

        HumanName name = thePatient.getNameFirstRep();

        String lastName = name.getFamily();
        String firstName = name.getGivenAsSingleString();
        String birthDate = thePatient.getBirthDateElement().asStringValue();
        if (StringUtils.isBlank(lastName) || StringUtils.isBlank(firstName) || StringUtils.isBlank(birthDate)) {
            return Collections.<IBaseResource>emptyList();
        }

        // Perform the search
        SearchParameterMap theParams = new SearchParameterMap();
        theParams.add(Patient.SP_FAMILY, new StringParam(lastName, true));
        theParams.add(Patient.SP_GIVEN, new StringParam(firstName, true));
        theParams.add(Patient.SP_BIRTHDATE, new DateParam(birthDate));
        theParams.setLoadSynchronousUpTo(100);

        // NOTE: there is issue while calling from JUNIT, ok from real server
        IBundleProvider provider = myPatientDao.search(theParams);
        int size = provider.size();
        List<IBaseResource> theOtherPatientList = provider.getResources(0, size);

        return theOtherPatientList;
    }
}
