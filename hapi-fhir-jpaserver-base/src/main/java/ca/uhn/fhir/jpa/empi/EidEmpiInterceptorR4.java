package ca.uhn.fhir.jpa.empi;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
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
import ca.uhn.fhir.rest.param.StringAndListParam;
import ca.uhn.fhir.rest.param.StringOrListParam;
import ca.uhn.fhir.rest.param.StringParam;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
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
public class EidEmpiInterceptorR4 extends ServerOperationInterceptorAdapter {

    private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(EidEmpiInterceptorR4.class);

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

    public EidEmpiInterceptorR4(String theMasterPatientIndexSystem) {
        super();

        Validate.notBlank(theMasterPatientIndexSystem);
        ourLog.info("EMPI system {}.", myEnterpriseIdentifierSystem);
        this.myEnterpriseIdentifierSystem = theMasterPatientIndexSystem;
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
        System.out.println("ENTER : resourcePreCreate()");
        
        if (myPatientDao.getContext().getResourceDefinition(theResource).getName().equals("Patient")) {
            System.out.println("This is patient resource");
        } else if (myPersonDao.getContext().getResourceDefinition(theResource).getName().equals("Person")) {
            System.out.println("This is person resource");
            
            Person thePerson = (Person)theResource;
            List<IBaseResource> getMatchedPersonList = getMatchedPerson(theRequest, thePerson);
            int size = getMatchedPersonList.size();
            
            System.out.println("size = " + size);
            if (size == 0) {
                injectEid(thePerson);              
            } else {
                throw new InvalidRequestException("The person with same name:" + thePerson.getNameFirstRep().getNameAsSingleString() + " and birthdate:" + thePerson.getBirthDateElement().getValueAsString());
            }
        } else if (myPractitionerDao.getContext().getResourceDefinition(theResource).getName().equals("Practitioner")) {
            System.out.println("This is practitioner resource");
        }
               
        System.out.println("EIXT  : resourcePreCreate()");
    }
    

    @Override
    public void resourceCreated(RequestDetails theRequest, IBaseResource theResource) {
        System.out.println("ENTER : resourceCreated()");

        if (myPatientDao.getContext().getResourceDefinition(theResource).getName().equals("Patient")) {
            System.out.println("This is patient resource");
            
            Patient thePatient = (Patient)theResource;
            // Step 1: 
            System.out.println("Step 1: Get matched person list.");
            List<IBaseResource> getMatchedPersonList = getMatchedPerson(thePatient);
            int size = getMatchedPersonList.size();
            
            if (size == 0) {
                System.out.println("Found zero -- create a Person with same name, birthdate");
                createPersonFromPatient(thePatient);                
            } else if (size == 1) {
                System.out.println("Found one");
                Person theMatchedPerson = (Person)getMatchedPersonList.get(0);
                linkPatientToThePerson(thePatient, theMatchedPerson);
            } else {
                System.out.println("Found multiple matched Person, something wrong.");
            }
        } else  if (myPersonDao.getContext().getResourceDefinition(theResource).getName().equals("Person")) {
            System.out.println("This is person resource");
        } else if (myPractitionerDao.getContext().getResourceDefinition(theResource).getName().equals("Practitioner")) {
            System.out.println("This is practitioner resource");
        }
      
        System.out.println("EIXT  : resourceCreated()");
    }

    @Override
    public void resourcePreUpdate(RequestDetails theRequest, IBaseResource theOldResource, IBaseResource theNewResource) {
        System.out.println("ENTER : resourcePreUpdate()");
        
        if (myPatientDao.getContext().getResourceDefinition(theNewResource).getName().equals("Patient")) {
            System.out.println("This is patient resource");
            
        } else  if (myPersonDao.getContext().getResourceDefinition(theNewResource).getName().equals("Person")) {
            System.out.println("This is person resource");
            Person theOldPerson = (Person)theOldResource;            
            System.out.println("Reference = " + theOldPerson.getLinkFirstRep().getTarget().getReference());
            
            Person theNewPerson = (Person)theNewResource;            
            System.out.println("Reference = " + theNewPerson.getLinkFirstRep().getTarget().getReference());
            
        } else if (myPractitionerDao.getContext().getResourceDefinition(theNewResource).getName().equals("Practitioner")) {
            System.out.println("This is practitioner resource");
        }
        
        System.out.println("EIXT  : resourcePreUpdate()");
    }
    
    @Override
    public void resourceUpdated(RequestDetails theRequest, IBaseResource theOldResource, IBaseResource theNewResource) {
        System.out.println("ENTER : resourceUpdated()");
        
        if (myPatientDao.getContext().getResourceDefinition(theNewResource).getName().equals("Patient")) {
            System.out.println("This is patient resource");
            
        } else  if (myPersonDao.getContext().getResourceDefinition(theNewResource).getName().equals("Person")) {
            System.out.println("This is person resource");
            Person theOldPerson = (Person)theOldResource;            
            System.out.println("Reference = " + theOldPerson.getLinkFirstRep().getTarget().getReference());
            
            Person theNewPerson = (Person)theNewResource;            
            System.out.println("Reference = " + theNewPerson.getLinkFirstRep().getTarget().getReference());
            System.out.println("Reference = " + theNewPerson.getLinkFirstRep().getTarget().getDisplay());
            
        } else if (myPractitionerDao.getContext().getResourceDefinition(theNewResource).getName().equals("Practitioner")) {
            System.out.println("This is practitioner resource");
        }
        
        System.out.println("EIXT  : resourceUpdated()");
    }

    // TODO need to make it configurable
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
    
    private List<IBaseResource> getMatchedPerson(RequestDetails theRequest, Person thePerson) {

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
        Reference target =  plc.getTarget();
        target.setReference(thePatientId);
        
        String lastName = thePatient.getNameFirstRep().getFamily();
        String firstName = thePatient.getNameFirstRep().getGivenAsSingleString();
        
        target.setDisplay(lastName + ", " + firstName);
        plc.setTarget(target);
               
        DaoMethodOutcome createdPerson =  myPersonDao.create(thePerson);
       
        return createdPerson.getId();
    }

    private void linkPatientToThePerson(Patient thePatient, Person thePerson) {
        
        System.out.println("ENTER : linkPatientToThePerson()");
        String thePatientId = thePatient.getId();
        
        PersonLinkComponent plc = thePerson.addLink();
        Reference target =  new Reference();
        target.setReference(thePatientId);
        
        String lastName = thePatient.getNameFirstRep().getFamily();
        String firstName = thePatient.getNameFirstRep().getGivenAsSingleString();
        
        target.setDisplay(lastName + ", " + firstName);
        plc.setTarget(target);
               
        myPersonDao.update(thePerson);
        
        System.out.println("EXIT  : linkPatientToThePerson()");
        return;
    }
    
    private void injectEid(Person thePerson) {

        Identifier theEid = thePerson.addIdentifier();
        theEid.setSystem(myEnterpriseIdentifierSystem);
        theEid.setValue(UUID.randomUUID().toString());

    }
}
