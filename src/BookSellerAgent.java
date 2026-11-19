package jadelab2;

import jade.core.Agent;
import jade.core.behaviours.*;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;

import java.util.*;
import jade.core.AID;

public class BookSellerAgent extends Agent {
	// private Hashtable catalogue;
	private Hashtable<String, ArrayList<BookCopy>> catalogue;
	private Hashtable<String, Reservation> reservations;
	// for ex 5, we need to store multiple copies
	private BookSellerGui myGui;

	private static class BookCopy {
		String title;
		int price;
		int shippingCost;

		BookCopy(String title, int price, int shippingCost) {
			this.title = title;
			this.price = price;
			this.shippingCost = shippingCost;
		}
	}

	private static class Reservation {
		BookCopy copy;
		AID buyer;
		long expiresAt;

		Reservation(BookCopy copy, AID buyer, long expiresAt) {
			this.copy = copy;
			this.buyer = buyer;
			this.expiresAt = expiresAt;
		}
	}

	protected void setup() {
		// catalogue = new Hashtable();
		catalogue = new Hashtable<String, ArrayList<BookCopy>>();
		reservations = new Hashtable<String, Reservation>();
		myGui = new BookSellerGui(this);
		myGui.display();

		// book selling service registration at DF
		DFAgentDescription dfd = new DFAgentDescription();
		dfd.setName(getAID());
		ServiceDescription sd = new ServiceDescription();
		sd.setType("book-selling");
		sd.setName("JADE-book-trading");
		dfd.addServices(sd);
		try {
			DFService.register(this, dfd);
		} catch (FIPAException fe) {
			fe.printStackTrace();
		}

		addBehaviour(new OfferRequestsServer());

		addBehaviour(new PurchaseOrdersServer());
	}

	protected void takeDown() {
		// book selling service deregistration at DF
		try {
			DFService.deregister(this);
		} catch (FIPAException fe) {
			fe.printStackTrace();
		}
		myGui.dispose();
		System.out.println("Seller agent " + getAID().getName() + " terminated.");
	}

	// invoked from GUI, when a new book is added to the catalogue
	public void updateCatalogue(final String title, final int price, final int shippingCost) {
		addBehaviour(new OneShotBehaviour() {
			public void action() {
				// catalogue.put(title, new int[] { price, shippingCost });
				ArrayList<BookCopy> copies = catalogue.get(title);
				if (copies == null) {
					copies = new ArrayList<BookCopy>();
					catalogue.put(title, copies);
				}

				copies.add(new BookCopy(title, price, shippingCost));
				System.out.println(
						getAID().getLocalName() + ": " + title
								+ " added. Price = " + price
								+ ", shipping = " + shippingCost);
			}
		});
	}

	//

	private void returnCopy(BookCopy copy) {
		ArrayList<BookCopy> copies = catalogue.get(copy.title);

		if (copies == null) {
			copies = new ArrayList<BookCopy>();
			catalogue.put(copy.title, copies);
		}

		copies.add(copy);
	}

	//

	private void releaseExpiredReservations() {
		long now = System.currentTimeMillis();

		Iterator<Map.Entry<String, Reservation>> iterator = reservations.entrySet().iterator();

		while (iterator.hasNext()) {
			Reservation reservation = iterator.next().getValue();

			if (reservation.expiresAt < now) {
				returnCopy(reservation.copy);
				iterator.remove();
			}
		}
	}

	//

	//
	private class OfferRequestsServer extends CyclicBehaviour {
		public void action() {
			// proposals only template
			MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.CFP);
			ACLMessage msg = myAgent.receive(mt);
			if (msg != null) {
				releaseExpiredReservations();
				String title = msg.getContent();
				ACLMessage reply = msg.createReply();
				// int[] offer = (int[]) catalogue.get(title);
				ArrayList<BookCopy> copies = catalogue.get(title);
				if (copies != null && !copies.isEmpty()) {
					BookCopy copy = copies.remove(0);
					if (copies.isEmpty()) {
						catalogue.remove(title);
					}
					String reservationId = UUID.randomUUID().toString();
					Reservation reservation = new Reservation(
							copy,
							msg.getSender(),
							System.currentTimeMillis() + 10000 // reservation expires in 10 seconds
					);
					reservations.put(reservationId, reservation);
					reply.setPerformative(ACLMessage.PROPOSE);
					reply.setContent(copy.price + "," + copy.shippingCost);
					reply.setReplyWith(reservationId);

				} else {
					// title not found in the catalogue
					reply.setPerformative(ACLMessage.REFUSE);
					reply.setContent("not-available");
				}
				myAgent.send(reply);
			} else {
				block();
			}
		}
	}

	private class PurchaseOrdersServer extends CyclicBehaviour {
		public void action() {
			// purchase order as proposal acceptance only template
			MessageTemplate mt = MessageTemplate.or(
					MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL),
					MessageTemplate.MatchPerformative(ACLMessage.REJECT_PROPOSAL));
			ACLMessage msg = myAgent.receive(mt);
			if (msg != null) {
				// String title = msg.getContent();
				releaseExpiredReservations();
				String reservationId = msg.getInReplyTo();
				Reservation reservation = reservations.remove(reservationId);
				if (msg.getPerformative() == ACLMessage.REJECT_PROPOSAL) {
					if (reservation != null) {
						returnCopy(reservation.copy);
						System.out.println(
								getAID().getLocalName()
										+ ": reservation for "
										+ reservation.copy.title
										+ " released");
					}

					return;
				}
				ACLMessage reply = msg.createReply();
				// int[] offer = (int[]) catalogue.remove(title);

				if (reservation != null && reservation.buyer.equals(msg.getSender())
						&& reservation.expiresAt >= System.currentTimeMillis()) {
					reply.setPerformative(ACLMessage.INFORM);
					System.out.println(
							getAID().getLocalName()
									+ ": reserved copy of "
									+ reservation.copy.title
									+ " sold to "
									+ msg.getSender().getLocalName()
									+ ". Price = "
									+ reservation.copy.price
									+ ", shipping = "
									+ reservation.copy.shippingCost);
				} else {
					if (reservation != null) {
						returnCopy(reservation.copy);
					}

					reply.setPerformative(ACLMessage.FAILURE);
					reply.setContent("reservation-expired");
				}
				myAgent.send(reply);
			} else {
				block();
			}
		}
	}

}
