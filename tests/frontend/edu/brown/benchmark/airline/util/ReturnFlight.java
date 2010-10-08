package edu.brown.benchmark.airline.util;

import java.util.Calendar;
import java.util.Date;

import edu.brown.benchmark.airline.AirlineConstants;

public class ReturnFlight implements Comparable<ReturnFlight> {
    
    private final CustomerId customer_id;
    private final long return_airport_id;
    private final Date return_date;
    
    public ReturnFlight(CustomerId customer_id, long return_airport_id, Date flight_date, int return_days) {
        this.customer_id = customer_id;
        this.return_airport_id = return_airport_id;
        this.return_date = ReturnFlight.calculateReturnDate(flight_date, return_days);
    }
    
    /**
     * 
     * @param flight_date
     * @param return_days
     * @return
     */
    protected static final Date calculateReturnDate(Date flight_date, int return_days) {
        assert(return_days >= 0);
        // Round this to the start of the day
        Calendar cal = Calendar.getInstance();
        cal.setTime(new Date(flight_date.getTime() + (return_days * AirlineConstants.MILISECONDS_PER_DAY)));
        
        int year = cal.get(Calendar.YEAR);
        int month= cal.get(Calendar.MONTH);
        int day = cal.get(Calendar.DAY_OF_MONTH);
        
        cal.clear();
        cal.set(year, month, day);
        return (cal.getTime());
    }
    
    /**
     * @return the customer_id
     */
    public CustomerId getCustomerId() {
        return customer_id;
    }

    /**
     * @return the return_airport_id
     */
    public long getReturnAirportId() {
        return return_airport_id;
    }

    /**
     * @return the return_time
     */
    public Date getReturnDate() {
        return return_date;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (obj instanceof ReturnFlight) {
            ReturnFlight o = (ReturnFlight)obj;
            return (this.customer_id.equals(o.customer_id) &&
                    this.return_airport_id == o.return_airport_id &&
                    this.return_date.equals(o.return_date));
        }
        return (false);
    }

    @Override
    public int compareTo(ReturnFlight o) {
        if (this.customer_id.equals(o.customer_id) &&
            this.return_airport_id == o.return_airport_id &&
            this.return_date.equals(o.return_date)) {
            return (0);
        }
        // Otherwise order by time
        return (this.return_date.compareTo(o.return_date));
    }

}