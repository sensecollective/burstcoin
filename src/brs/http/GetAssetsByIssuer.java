package brs.http;

import brs.Account;
import brs.Asset;
import brs.db.BurstIterator;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONStreamAware;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

public final class GetAssetsByIssuer extends APIServlet.APIRequestHandler {

  static final GetAssetsByIssuer instance = new GetAssetsByIssuer();

  private GetAssetsByIssuer() {
    super(new APITag[] {APITag.AE, APITag.ACCOUNTS}, "account", "account", "account", "firstIndex", "lastIndex");
  }

  @Override
  JSONStreamAware processRequest(HttpServletRequest req) throws ParameterException {
    List<Account> accounts = ParameterParser.getAccounts(req);
    int firstIndex = ParameterParser.getFirstIndex(req);
    int lastIndex = ParameterParser.getLastIndex(req);

    JSONObject response = new JSONObject();
    JSONArray accountsJSONArray = new JSONArray();
    response.put("assets", accountsJSONArray);
    for (Account account : accounts) {
      JSONArray assetsJSONArray = new JSONArray();
      try (BurstIterator<Asset> assets = Asset.getAssetsIssuedBy(account.getId(), firstIndex, lastIndex)) {
        while (assets.hasNext()) {
          assetsJSONArray.add(JSONData.asset(assets.next()));
        }
      }
      accountsJSONArray.add(assetsJSONArray);
    }
    return response;
  }

}
