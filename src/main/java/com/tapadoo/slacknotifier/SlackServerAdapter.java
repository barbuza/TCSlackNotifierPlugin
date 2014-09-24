package com.tapadoo.slacknotifier;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import jetbrains.buildServer.serverSide.BuildServerAdapter;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SBuildServer;
import jetbrains.buildServer.serverSide.SRunningBuild;
import jetbrains.buildServer.serverSide.settings.ProjectSettingsManager;
import jetbrains.buildServer.users.SUser;
import jetbrains.buildServer.users.User;
import jetbrains.buildServer.users.UserSet;
import jetbrains.buildServer.vcs.SVcsModification;
import jetbrains.buildServer.vcs.SelectPrevBuildPolicy;
import org.joda.time.Duration;
import org.joda.time.format.PeriodFormatter;
import org.joda.time.format.PeriodFormatterBuilder;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Iterator;
import java.util.List;


/**
 * Created by jasonconnery on 02/03/2014.
 */
public class SlackServerAdapter extends BuildServerAdapter {

  private final SBuildServer buildServer;
  private final SlackConfigProcessor slackConfig;
  private final ProjectSettingsManager projectSettingsManager;
  private final ProjectManager projectManager;
  private Gson gson;

  public SlackServerAdapter(SBuildServer sBuildServer, ProjectManager projectManager, ProjectSettingsManager projectSettingsManager, SlackConfigProcessor configProcessor) {

    this.projectManager = projectManager;
    this.projectSettingsManager = projectSettingsManager;
    this.buildServer = sBuildServer;
    this.slackConfig = configProcessor;

  }

  public void init() {
    buildServer.addListener(this);
  }

  private Gson getGson() {
    if (gson == null) {
      gson = new GsonBuilder().create();
    }

    return gson;
  }

  @Override
  public void buildStarted(SRunningBuild build) {
    super.buildStarted(build);

    if (!build.isPersonal() && slackConfig.postStarted()) {
      postStartedBuild(build);
    }
  }

  @Override
  public void buildFinished(SRunningBuild build) {
    super.buildFinished(build);

    if (!build.isPersonal() && build.getBuildStatus().isSuccessful() && slackConfig.postSuccessful()) {
      processSuccessfulBuild(build);
    } else if (!build.isPersonal() && build.getBuildStatus().isFailed() && slackConfig.postFailed()) {
      postFailureBuild(build);
    } else {
      //TODO - modify in future if we care about other states
    }
  }

  private void postStartedBuild(SRunningBuild build) {
    //Could put other into here. Agents maybe?
    String message = String.format("Project '%s' build started.", build.getFullName());
    postToSlack(build, message, true);
  }

  private void postFailureBuild(SRunningBuild build) {
    String message = "";

    PeriodFormatter durationFormatter = new PeriodFormatterBuilder()
        .printZeroRarelyFirst()
        .appendHours()
        .appendSuffix(" часа", " часова")
        .appendSeparator(" ")
        .printZeroRarelyLast()
        .appendMinutes()
        .appendSuffix(" минтунама", " минут")
        .appendSeparator(" и ")
        .appendSeconds()
        .appendSuffix(" скунда", " скунданама")
        .toFormatter();

    Duration buildDuration = new Duration(1000 * build.getDuration());

    message = String.format("Проектамана '%s' сломата! ( %s )", build.getFullName(), durationFormatter.print(buildDuration.toPeriod()));

    postToSlack(build, message, false);
  }

  private void processSuccessfulBuild(SRunningBuild build) {

    String message = "";

    PeriodFormatter durationFormatter = new PeriodFormatterBuilder()
        .printZeroRarelyFirst()
        .appendHours()
        .appendSuffix(" часа", " часова")
        .appendSeparator(" ")
        .printZeroRarelyLast()
        .appendMinutes()
        .appendSuffix(" минтунама", " минут")
        .appendSeparator(" и ")
        .appendSeconds()
        .appendSuffix(" скунда", " скунданама")
        .toFormatter();

    Duration buildDuration = new Duration(1000 * build.getDuration());

    message = String.format("Проектамана '%s' собрала карашонама %s.", build.getFullName(), durationFormatter.print(buildDuration.toPeriod()));

    postToSlack(build, message, true);
  }

  /**
   * Post a payload to slack with a message and good/bad color. Commiter summary is automatically added as an attachment
   *
   * @param build     the build the message is relating to
   * @param message   main message to include, 'Build X completed...' etc
   * @param goodColor true for 'good' builds, false for danger.
   */
  private void postToSlack(SRunningBuild build, String message, boolean goodColor) {
    try {

      String finalUrl = slackConfig.getPostUrl() + slackConfig.getToken();
      URL url = new URL(finalUrl);


      SlackProjectSettings projectSettings = (SlackProjectSettings) projectSettingsManager.getSettings(build.getProjectId(), "slackSettings");

      if (!projectSettings.isEnabled()) {
        return;
      }


      String configuredChannel = build.getParametersProvider().get("SLACK_CHANNEL");
      String channel = this.slackConfig.getDefaultChannel();

      if (configuredChannel != null && configuredChannel.length() > 0) {
        channel = configuredChannel;
      } else if (projectSettings != null && projectSettings.getChannel() != null && projectSettings.getChannel().length() > 0) {
        channel = projectSettings.getChannel();
      }

      List<SVcsModification> changes = build.getContainingChanges();

//      UserSet<SUser> commiters = build.getCommitters(SelectPrevBuildPolicy.SINCE_LAST_BUILD);
//      StringBuilder committersString = new StringBuilder();
//
//      for (SUser commiter : commiters.getUsers()) {
//        if (commiter != null) {
//          String commiterName = commiter.getName();
//          if (commiterName == null || commiterName.equals("")) {
//            commiterName = commiter.getUsername();
//          }
//
//          if (commiterName != null && !commiterName.equals("")) {
//            committersString.append(commiterName);
//            committersString.append(",");
//          }
//        }
//      }
//
//      if (committersString.length() > 0) {
//        committersString.deleteCharAt(committersString.length() - 1); //remove the last ,
//      }
//
//      String commitMsg = committersString.toString();


      JsonObject payloadObj = new JsonObject();
      payloadObj.addProperty("channel", channel);
      // payloadObj.addProperty("username" , "TeamCity");
      payloadObj.addProperty("text", message);
      // payloadObj.addProperty("icon_url",slackConfig.getLogoUrl());

      if (changes.size() > 0) {
        JsonArray attachmentsObj = new JsonArray();

        Iterator<SVcsModification> iter = changes.iterator();

        while(iter.hasNext()) {
          SVcsModification commit = iter.next();

          JsonObject attachment = new JsonObject();

          // TODO: fallback message
          attachment.addProperty("fallback", "commit details");
          attachment.addProperty("color", (goodColor ? "good" : "danger"));

          JsonArray fields = new JsonArray();
          JsonObject field = new JsonObject();

          // TODO: is this correct?
          String users = "";
          Iterator<SUser> usersIter = commit.getCommitters().iterator();
          while (usersIter.hasNext()) {
            SUser user = usersIter.next();
            if (users.length() > 0) {
              users += ", ";
            }
            users += user.getName();
          }

          if (users.length() == 0) {
            continue;
          }

          field.addProperty("title", "Changes By");
          field.addProperty("value", users);
          field.addProperty("short", true);

          fields.add(field);

          field = new JsonObject();
          field.addProperty("title", "Message");
          field.addProperty("value", commit.getDescription());
          field.addProperty("short", true);

          fields.add(field);

          attachment.add("fields", fields);

          attachmentsObj.add(attachment);
        }

        //Could put other into here as attachments. Agents maybe? No point?
        payloadObj.add("attachments", attachmentsObj);
      }

      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      conn.setDoOutput(true);

      BufferedOutputStream bos = new BufferedOutputStream(conn.getOutputStream());

      String payloadJson = getGson().toJson(payloadObj);
      String bodyContents = "payload=" + payloadJson;
      bos.write(bodyContents.getBytes("utf8"));
      bos.flush();
      bos.close();

      int serverResponseCode = conn.getResponseCode();

      conn.disconnect();
      conn = null;
      url = null;

    } catch (MalformedURLException ex) {

    } catch (IOException e) {
      e.printStackTrace();
    }
  }

}
