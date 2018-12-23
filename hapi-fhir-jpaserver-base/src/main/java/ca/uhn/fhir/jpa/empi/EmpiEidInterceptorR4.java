package ca.uhn.fhir.jpa.empi;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.hl7.fhir.instance.model.api.IBaseCoding;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Person;
import org.hl7.fhir.r4.model.Person.PersonLinkComponent;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.Reference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import ca.uhn.fhir.jpa.dao.DaoMethodOutcome;
import ca.uhn.fhir.jpa.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.dao.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.DateParam;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.StringAndListParam;
import ca.uhn.fhir.rest.param.StringOrListParam;
import ca.uhn.fhir.rest.param.StringParam;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
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
public class EmpiEidInterceptorR4 extends ServerOperationInterceptorAdapter {

    private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(EmpiEidInterceptorR4.class);

    // -- TODO move to common place
    public static final String TAG_DUP_PERSON = "dupPerson";
    public static final String TAG_FORCE_EID = "forceEid"; // When create a
                                                           // Person, do not
                                                           // search for the
                                                           // matched person,
                                                           // create eid that
                                                           // person

    @Autowired
    @Qualifier("myPatientDaoR4")
    private IFhirResourceDao<Patient> myPatientDao;

    @Autowired
    @Qualifier("myPersonDaoR4")
    private IFhirResourceDao<Person> myPersonDao;

    @Autowired
    @Qualifier("myPractitionerDaoR4")
    private IFhirResourceDao<Practitioner> myPractitionerDao;

    private String myEnterpriseIdentifierSystem;
    private String myTagSystem;

    public EmpiEidInterceptorR4(String theEnterpriseIdentifierSystem, String theTagSystem) {
        super();

        Validate.notBlank(theEnterpriseIdentifierSystem);
        Validate.notBlank(theTagSystem);

        ourLog.info("theEnterpriseIdentifierSystem={}.", theEnterpriseIdentifierSystem);
        this.myEnterpriseIdentifierSystem = theEnterpriseIdentifierSystem;

        ourLog.info("theTagSystem={}.", theTagSystem);
        this.myTagSystem = theTagSystem;
    }

    public void setMyPatientDao(IFhirResourceDao<Patient> myPatientDao) {
        this.myPatientDao = myPatientDao;
    }

    public void setMyPersonDao(IFhirResourceDao<Person> myPersonDao) {
        this.myPersonDao = myPersonDao;
    }

    public void setMyPractitionerDao(IFhirResourceDao<Practitioner> myPractitionerDao) {
        this.myPractitionerDao = myPractitionerDao;
    }

    @Override
    public void resourcePreCreate(RequestDetails theRequest, IBaseResource theResource) {

        if (myPersonDao.getContext().getResourceDefinition(theResource).getName().equals("Person")) {

            Person thePerson = (Person) theResource;
            if (hasTag(thePerson, TAG_FORCE_EID)) {
                // do the force eid logic
                injectEid(thePerson, null); // create new EID
            } else {

                // Duplicate check
                List<IBaseResource> matchedPersonList = getMatchedPerson(thePerson);
                int size = matchedPersonList.size();

                if (size == 0) {
                    injectEid(thePerson, null); // create new EID
                } else {
                    injectTag(thePerson, TAG_DUP_PERSON, "Same name:" + thePerson.getNameFirstRep().getNameAsSingleString() + " and birthdate:" + thePerson.getBirthDateElement().getValueAsString());
                }

                // -- inject the tag for all matched person
                for (IBaseResource matchedResouce : matchedPersonList) {
                    Person matchedPerson = (Person) matchedResouce;
                    if (!hasTag(matchedPerson, TAG_DUP_PERSON)) {
                        injectTag(matchedPerson, TAG_DUP_PERSON,
                                "Same name:" + matchedPerson.getNameFirstRep().getNameAsSingleString() + " and birthdate:" + matchedPerson.getBirthDateElement().getValueAsString());
                        myPersonDao.update(matchedPerson);
                    }
                }
            }
        } else if (myPatientDao.getContext().getResourceDefinition(theResource).getName().equals("Patient")) {

            Patient thePatient = (Patient) theResource;
            // -- Get matched person list.
            List<IBaseResource> matchedPersonList = getMatchedPerson(thePatient);
            int size = matchedPersonList.size();

            // -- not very efficient, search the person twice, in precreate and
            // created
            // -- how do i pass the result over?
            if (size > 1) {
                // found multiple person, inject the tag on person
                injectTag(thePatient, TAG_DUP_PERSON, "Same name:" + thePatient.getNameFirstRep().getNameAsSingleString() + " and birthdate:" + thePatient.getBirthDateElement().getValueAsString());
            }
        }
    }

    @Override
    public void resourceCreated(RequestDetails theRequest, IBaseResource theResource) {

        if (myPatientDao.getContext().getResourceDefinition(theResource).getName().equals("Patient")) {

            Patient thePatient = (Patient) theResource;
            // -- Get matched person list.
            List<IBaseResource> matchedPersonList = getMatchedPerson(thePatient);
            int size = matchedPersonList.size();

            if (size == 0) {
                // No Person with same name and birth date found from this
                // patient.
                // create the Person, and linked the patient to the person
                createPersonFromPatient(thePatient);
            } else if (size == 1) {
                // Found one person with same name and birth date.
                // link the patient to the person
                Person theMatchedPerson = (Person) matchedPersonList.get(0);
                linkPatientToThePerson(thePatient, theMatchedPerson);
            } else {
                // do nothing, in the preCreated, the dup_person tag is injected
            }
        }

    }

    @Override
    public void resourcePreUpdate(RequestDetails theRequest, IBaseResource theOldResource, IBaseResource theNewResource) {

        if (myPersonDao.getContext().getResourceDefinition(theNewResource).getName().equals("Person")) {

            // 1. if the new person has EID, do nothing
            Person theNewPerson = (Person) theNewResource;
            if (getEid(theNewPerson) != null)
                return;

            // 2. otherwise, copy the EID from old resource
            Person theOldPerson = (Person) theOldResource;
            String theOldEid = getEid(theOldPerson);
            if (theOldEid != null) {
                // found theOidEid, inject the old Eid to the new patient
                injectEid(theNewPerson, theOldEid);
                return;
            }

            // 3. create new EID if there is no EID from old resource
            injectEid(theNewPerson, null); // inject new EID
        }

    }

    @Override
    public void resourceUpdated(RequestDetails theRequest, IBaseResource theOldResource, IBaseResource theNewResource) {

        if (myPatientDao.getContext().getResourceDefinition(theNewResource).getName().equals("Patient")) {

            Patient thePatient = (Patient) theNewResource;
            // -- Get matched person list.
            List<IBaseResource> matchedPersonList = getPersonByPatientId(thePatient.getId());
            int size = matchedPersonList.size();

            if (size == 0) {
                // The Patient is not linked to and Person
                // create the Person, and linked the patient to the person
                createPersonFromPatient(thePatient);
            } else if (size == 1) {
                // Found one person with linked to this Patient.
                // do nothing
                return;
            } else {
                // Found multiple person linked to this Patient,
                // something wrong
                throw new UnprocessableEntityException("Found multiple matched person linked to the patinet : " + thePatient.getId());
            }

        }
    }

    private List<IBaseResource> getMatchedPerson(Patient thePatient) {

        HumanName name = thePatient.getNameFirstRep();

        String lastName = name.getFamily();
        String firstName = name.getGivenAsSingleString();
        String birthDate = thePatient.getBirthDateElement().asStringValue();
        if (StringUtils.isBlank(lastName) || StringUtils.isBlank(firstName) || StringUtils.isBlank(birthDate)) {
            return Collections.<IBaseResource>emptyList();
        }

        // Perform the search
        StringAndListParam theNameParams = new StringAndListParam();
        StringParam lastNameParam = new StringParam(lastName, true);
        StringParam firstNameParam = new StringParam(firstName, true);

        theNameParams.addAnd(new StringOrListParam().addOr(lastNameParam).addOr(firstNameParam));

        SearchParameterMap theParams = new SearchParameterMap();
        theParams.add(Person.SP_NAME, theNameParams);
        theParams.add(Person.SP_BIRTHDATE, new DateParam(birthDate));
        theParams.setLoadSynchronousUpTo(10);

        IBundleProvider provider = myPersonDao.search(theParams);
        List<IBaseResource> theOtherPatientList = provider.getResources(0, provider.size());

        return theOtherPatientList;
    }

    private List<IBaseResource> getPersonByPatientId(String patientId) {

        SearchParameterMap theParams = new SearchParameterMap();
        ReferenceParam refParam = new ReferenceParam();
        refParam.setValue(patientId);
        theParams.add(Person.SP_LINK, refParam);
        theParams.setLoadSynchronousUpTo(10);

        IBundleProvider provider = myPersonDao.search(theParams);
        List<IBaseResource> theOtherPatientList = provider.getResources(0, provider.size());

        return theOtherPatientList;
    }

    private List<IBaseResource> getMatchedPerson(Person thePerson) {

        HumanName name = thePerson.getNameFirstRep();

        String lastName = name.getFamily();
        String firstName = name.getGivenAsSingleString();
        String birthDate = thePerson.getBirthDateElement().asStringValue();
        if (StringUtils.isBlank(lastName) || StringUtils.isBlank(firstName) || StringUtils.isBlank(birthDate)) {
            return Collections.<IBaseResource>emptyList();
        }

        // Perform the search
        StringAndListParam theNameParams = new StringAndListParam();
        StringParam lastNameParam = new StringParam(lastName, true);
        StringParam firstNameParam = new StringParam(firstName, true);

        theNameParams.addAnd(new StringOrListParam().addOr(lastNameParam).addOr(firstNameParam));

        SearchParameterMap theParams = new SearchParameterMap();
        theParams.add(Person.SP_NAME, theNameParams);
        theParams.add(Person.SP_BIRTHDATE, new DateParam(birthDate));
        theParams.setLoadSynchronousUpTo(10);

        IBundleProvider provider = myPersonDao.search(theParams);
        List<IBaseResource> theOtherPatientList = provider.getResources(0, provider.size());

        return theOtherPatientList;
    }

    private IIdType createPersonFromPatient(Patient thePatient) {

        String thePatientId = thePatient.getId();

        Person thePerson = new Person();
        thePerson.addName(thePatient.getNameFirstRep());
        thePerson.setBirthDate(thePatient.getBirthDateElement().getValue());

        Identifier theEid = thePerson.addIdentifier();
        theEid.setSystem(myEnterpriseIdentifierSystem);
        theEid.setValue(UUID.randomUUID().toString());

        PersonLinkComponent plc = thePerson.addLink();
        Reference target = plc.getTarget();
        target.setReference(thePatientId);

        String lastName = thePatient.getNameFirstRep().getFamily();
        String firstName = thePatient.getNameFirstRep().getGivenAsSingleString();

        target.setDisplay(lastName + ", " + firstName);
        plc.setTarget(target);

        DaoMethodOutcome createdPerson = myPersonDao.create(thePerson);

        return createdPerson.getId();
    }

    private void linkPatientToThePerson(Patient thePatient, Person thePerson) {

        String thePatientId = thePatient.getId();

        PersonLinkComponent plc = thePerson.addLink();
        Reference target = new Reference();
        target.setReference(thePatientId);

        String lastName = thePatient.getNameFirstRep().getFamily();
        String firstName = thePatient.getNameFirstRep().getGivenAsSingleString();

        target.setDisplay(lastName + ", " + firstName);
        plc.setTarget(target);

        myPersonDao.update(thePerson);

        return;
    }

    private void injectEid(Person thePerson, String eid) {

        // injectEid if it does not exists
        if (getEid(thePerson) == null) {
            Identifier theEid = thePerson.addIdentifier();
            theEid.setSystem(myEnterpriseIdentifierSystem);
            if (eid == null)
                theEid.setValue(UUID.randomUUID().toString());
            else
                theEid.setValue(eid);
        }
    }

    private void injectTag(IBaseResource theResource, String theTagCode, String theTagDisplay) {

        IBaseCoding theTag = theResource.getMeta().addTag();
        theTag.setSystem(myTagSystem);
        theTag.setCode(theTagCode);
        theTag.setDisplay(theTagDisplay);
    }

    private boolean hasTag(IBaseResource theResource, String theTagCode) {

        IBaseCoding theTag = theResource.getMeta().getTag(myTagSystem, theTagCode);
        if (theTag == null)
            return false;
        return true;
    }

    private String getEid(Person thePerson) {

        List<Identifier> identifierList = thePerson.getIdentifier();
        String eid;
        for (Identifier identifier : identifierList) {

            if (!myEnterpriseIdentifierSystem.equals(identifier.getSystem()))
                continue;

            eid = identifier.getValue();
            if (eid == null)
                continue;

            return eid;
        }

        return null;
    }

}
