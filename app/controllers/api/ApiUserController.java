package controllers.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.paypal.api.payments.*;
import com.paypal.base.rest.APIContext;
import com.paypal.base.rest.OAuthTokenCredential;
import com.paypal.base.rest.PayPalRESTException;
import helpers.Authenticators;
import helpers.ReservationStatus;
import models.AppUser;
import models.Reservation;
import models.Room;
import play.Logger;
import play.Play;
import play.data.DynamicForm;
import play.data.Form;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Result;
import play.mvc.Security;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

public class ApiUserController extends Controller {

    private static final Form<AppUser> userForm = Form.form(AppUser.class);
    private static PaymentExecution paymentExecution;
    private static Form<Reservation> reservationForm = Form.form(Reservation.class);

    public Result login() {

        Form<AppUser> boundForm = userForm.bindFromRequest();

        String email = boundForm.field("email").value();
        String password = boundForm.field("password").value();

        AppUser user = AppUser.authenticate(email, password);

        if(user != null) {
            return ok(Json.toJson(user.id));
        }
        return unauthorized();
    }

    public Result getAllUsers() {
        List<AppUser> users = AppUser.finder.all();
        return ok(Json.toJson(users));
    }

    public Result payPal(Integer roomId) {

        //   AppUser user = AppUser.findUserById(Integer.parseInt(session("userId")));
        Form<Reservation> boundForm = reservationForm.bindFromRequest();
        AppUser user = AppUser.findUserById(Integer.parseInt(boundForm.field("userId").value()));
        String checkin = boundForm.field("checkIn").value();
        String checkout = boundForm.field("checkOut").value();

        Room room = Room.findRoomById(roomId);
        Reservation reservation = new Reservation();
        reservation.room = room;
        reservation.user = user;
        reservation.setCreatedBy(user.firstname, user.lastname);
        SimpleDateFormat dtf = new SimpleDateFormat("dd/MM/yyyy");
        try {
            Date firstDate = dtf.parse(checkin);
            Date secondDate = dtf.parse(checkout);
            if (firstDate.before(secondDate)) {
                reservation.checkIn = firstDate;
                reservation.checkOut = secondDate;
                reservation.cost = reservation.getCost();
            } else {
                flash("error", "Check in date can't be after check out date!");
                Logger.info("---------------------1-----------");
                return redirect("/");
            }


            // Configuration
            String clientid = Play.application().configuration().getString("clientId");
            String secret = Play.application().configuration().getString("clientSecret");

            String token = new OAuthTokenCredential(clientid, secret).getAccessToken();

            Map<String, String> config = new HashMap<>();
            config.put("mode", "sandbox");

            APIContext context = new APIContext(token);
            context.setConfigurationMap(config);

            // Process cart/payment information

            double price = reservation.cost.doubleValue();

            String priceString = String.format("%1.2f", price);

            String desc = "Costumer name: " + reservation.createdBy + "\n" +
                    "Reservation for hotel: " + room.hotel.name + "\n " +
                    "Check-in: " + reservation.checkIn + "\n" +
                    "Check-out" + reservation.checkOut + "\n" +
                    "Amount: " + priceString;
            // Configure payment
            Amount amount = new Amount();
            amount.setTotal(priceString);
            amount.setCurrency("USD");

            List<Transaction> transactionList = new ArrayList<>();
            Transaction transaction = new Transaction();
            transaction.setAmount(amount);
            transaction.setDescription(desc);
            transactionList.add(transaction);

            Payer payer = new Payer();
            payer.setPaymentMethod("paypal");

            Payment payment = new Payment();
            payment.setPayer(payer);
            payment.setIntent("sale");
            payment.setTransactions(transactionList);

            RedirectUrls redirects = new RedirectUrls();
            redirects.setCancelUrl("http://localhost:9000/rejectPayment");
            redirects.setReturnUrl("http://localhost:9000/paypal/success");

            payment.setRedirectUrls(redirects);

            Payment madePayments = payment.create(context);
            String id = madePayments.getId();
            reservation.payment_id = id;


            reservation.status = ReservationStatus.PENDING;
            reservation.save();

            Iterator<Links> it = madePayments.getLinks().iterator();
            while (it.hasNext()) {
                Links link = it.next();
                if (link.getRel().equals("approval_url")) {
                    Logger.info("---------------------2-----------");
                    return redirect(link.getHref());
                }
            }

            Payment newPayment = payment.execute(context, paymentExecution );
            Logger.info("new PAYMENT " + newPayment);

        } catch (PayPalRESTException e) {
            Logger.warn("PayPal Exception");
            e.printStackTrace();
        } catch (ParseException ex) {
            System.out.println(ex.getMessage());
        }
        Logger.info("---------------------3-----------");
        return redirect("/");
    }

    public Result paypalSuccess() {

        APIContext context;
        PaymentExecution paymentExecution;
        Payment payment;
        DynamicForm form = Form.form().bindFromRequest();
        String paymentId = form.data().get("paymentId");
        String payerID = form.data().get("PayerID");
        String clientId = Play.application().configuration().getString("clientId");
        String secret = Play.application().configuration().getString("clientSecret");
        try {
            String accessToken = new OAuthTokenCredential(clientId,
                    secret).getAccessToken();
            Map<String, String> sdkConfig = new HashMap<String, String>();
            sdkConfig.put("mode", "sandbox");
            context = new APIContext(accessToken);
            context.setConfigurationMap(sdkConfig);
            payment = Payment.get(accessToken, paymentId);
            paymentExecution = new PaymentExecution();
            paymentExecution.setPayerId(payerID);

            //Executes a payment
            Payment response = payment.execute(context, paymentExecution);
            String saleId = response.getTransactions().get(0).getRelatedResources().get(0).getSale().getId();

            Reservation reservation = Reservation.findByPaymentId(paymentId);
            reservation.status = ReservationStatus.APPROVED;
            reservation.sale_id = saleId;
            reservation.update();

            Room room = Room.findRoomById(reservation.room.id);
            room.roomType -= 1;
            room.update();
            flash("info");

        } catch (Exception e) {
            flash("error");
            Logger.debug("Error at purchaseSucess: " + e.getMessage(), e);
            return redirect("/");
        }

        return ok(views.html.user.successfulPayment.render());
    }

}
