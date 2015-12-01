package de.ml.endpoints;

import org.apache.camel.Endpoint;

/**
 * specifies all provided rest endpoints.
 * @author moritz
 *
 */
public interface RestEndpoints {

    Endpoint next();

    Endpoint nextAuto();

    Endpoint nextAutoTime();

    Endpoint update();

    Endpoint prev();

    Endpoint info();

    Endpoint current();

    Endpoint filterName();

    Endpoint filterNameAuto();

    Endpoint filterNameInfo();

    Endpoint filterNameAutoTime();

}