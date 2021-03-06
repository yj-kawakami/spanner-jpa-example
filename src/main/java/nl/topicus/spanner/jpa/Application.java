package nl.topicus.spanner.jpa;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.PageRequest;
import nl.topicus.jdbc.CloudSpannerDriver;
import nl.topicus.spanner.jpa.entities.Customer;
import nl.topicus.spanner.jpa.entities.CustomerRepository;
import nl.topicus.spanner.jpa.entities.FormattedCustomerProjection;
import nl.topicus.spanner.jpa.entities.Invoice;
import nl.topicus.spanner.jpa.entities.InvoiceRepository;
import nl.topicus.spanner.jpa.entities.PhoneRepository;
import nl.topicus.spanner.jpa.service.EntityService;

@SpringBootApplication
public class Application {
  private static final Logger log = LoggerFactory.getLogger(Application.class);


  @Autowired
  private EntityService service;

  public static void main(String[] args) throws IOException {
    // turn on JDBC debug logging to be able to find any (too) long running transactions
    // long transactions are prone to be aborted by Cloud Spanner, and you should always try to keep
    // your transactions as short as possible

    // Set the log level. DEBUG means that the stack trace that started a long-running transaction
    // will also be logged. This makes it a lot easier to find the piece of code that is causing the
    // long-running transaction, especially if it is a transaction that is unintentionally started.
    CloudSpannerDriver.setLogLevel(CloudSpannerDriver.DEBUG);
    // Set a JDBC log writer. In this case the log file will be overwritten everytime you start the
    // application.
    Writer writer = new FileWriter("jdbc.log", false);
    DriverManager.setLogWriter(new PrintWriter(writer));

    SpringApplication.run(Application.class);
  }

  @Bean
  public CommandLineRunner demo(CustomerRepository customerRepo, InvoiceRepository invoiceRepo,
      PhoneRepository phoneRepo) {
    return (args) -> {
      // reset repositories
      invoiceRepo.deleteAll();
      customerRepo.deleteAll();
      // save a couple of customers
      List<Long> customerIds = new ArrayList<>();
      customerIds.add(customerRepo.save(new Customer("Jack", "Bauer")).getId());
      // Get the commit timestamp to compare to created_at value later
      // Note that this call does not actually go to the database, the JDBC driver has stored this
      // in memory
      Timestamp commitTimestampJackBauer = customerRepo.getLastCommitTimestamp();
      customerIds.add(customerRepo.save(new Customer("Chloe", "O'Brian")).getId());
      customerIds.add(customerRepo.save(new Customer("Kim", "Bauer")).getId());
      Timestamp commitTimestampKimBauer = customerRepo.getLastCommitTimestamp();
      customerIds.add(customerRepo.save(new Customer("David", "Palmer")).getId());
      customerIds.add(customerRepo.save(new Customer("Michelle", "Dessler")).getId());

      byte[] pdf =
          Files.readAllBytes(Paths.get(Application.class.getResource("pdf-sample.pdf").toURI()));
      List<Long> invoiceIds = new ArrayList<>();
      invoiceIds.add(invoiceRepo.save(new Invoice(customerRepo.findById(customerIds.get(0)).get(),
          "001", "Some invoice", BigDecimal.valueOf(29.50), pdf)).getId());
      invoiceIds.add(invoiceRepo.save(new Invoice(customerRepo.findById(customerIds.get(1)).get(),
          "002", "Some invoice 2", BigDecimal.valueOf(100.00), pdf)).getId());

      // create phones
      for (Long customerId : customerIds) {
        service.addPhone(customerId, 123);
      }
      // update phones
      for (Long customerId : customerIds) {
        service.setPhones(customerId, Arrays.asList(1, 2, 3));
      }

      // fetch all customers
      log.info("Customers found with findAll():");
      log.info("-------------------------------");
      for (Customer customer : customerRepo.findAll()) {
        log.info(customer.toString());
        service.printPhones(customer.getId());
      }
      log.info("");

      // fetch an individual customer by ID
      Customer customer = customerRepo.findById(customerIds.get(0)).get();
      log.info("Customer found with findOne(" + customerIds.get(0) + "):");
      log.info("--------------------------------");
      log.info(customer.toString());
      log.info("");

      // fetch customers by last name and compare created/updated
      // timestamps with commit timestamp
      log.info("Customer found with findByLastName('Bauer'):");
      log.info("--------------------------------------------");
      for (Customer bauer : customerRepo.findByLastName("Bauer")) {
        log.info(bauer.toString());
        log.info(bauer.getFullName());
        log.info("created at: " + bauer.getCreated());
        log.info("updated at: " + bauer.getUpdated());
        if (bauer.getFirstName().equals("Jack")) {
          log.info("Jack Bauer commit timestamp: " + commitTimestampJackBauer);
        } else if (bauer.getFirstName().equals("Kim")) {
          log.info("Kim Bauer commit timestamp: " + commitTimestampKimBauer);
        }
      }
      log.info("");

      // fetch customers using custom query
      log.info("Customer found with custom query (no results should be found):");
      log.info("--------------------------------------------");
      for (Customer c : customerRepo.findCustomer(PageRequest.of(2, 100))) {
        log.info(c.toString());
      }
      log.info("");

      // fetch customers using by full name
      log.info("Customer found by full name (concat) query:");
      log.info("--------------------------------------------");
      for (Customer c : customerRepo.findCustomerByConcatFullName(PageRequest.of(0, 100),
          "Jack Bauer")) {
        log.info(c.toString());
      }
      log.info("");

      log.info("Invoice found by number and description (index hint) query:");
      log.info("--------------------------------------------");
      for (Invoice i : invoiceRepo.findInvoiceByNumberAndDescription(PageRequest.of(0, 100), "001",
          "Some invoice")) {
        log.info(i.toString());
      }
      log.info("");

      log.info("Customer found by full name automatic query:");
      log.info("--------------------------------------------");
      for (Customer c : customerRepo.findCustomerByFullName(PageRequest.of(0, 100), "Jack Bauer")) {
        log.info(c.toString());
      }
      log.info("");

      // fetch customers with concatenated customer code
      log.info("Customer list with formatted customer code:");
      log.info("--------------------------------------------");
      for (FormattedCustomerProjection c : customerRepo.listCustomerProjections()) {
        log.info(c.getCode() + " | " + c.getFormattedCode());
      }
      log.info("");

      // fetch invoices
      Invoice invoice = invoiceRepo.findById(invoiceIds.get(0)).get();
      log.info("Invoice found with findOne(" + invoiceIds.get(0) + "):");
      log.info("--------------------------------");
      log.info(invoice.toString());
      log.info("");

      // set the long transaction trigger to 1 second (default is 10 seconds) to force the logging
      // of a 'long-running'transaction
      CloudSpannerDriver.setLongTransactionTrigger(1000L);
      log.info("Forcing long-running transaction");
      service.findAllCustomersSlow();
      log.info("Finished long-running transaction");
    };
  }
}
