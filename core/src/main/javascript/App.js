// @flow

import React from "react";
import { connect } from "react-redux";
import injectSheet from "react-jss";
import _ from "lodash";
import FilterIcon from "react-icons/lib/md/search";

import MenuHeader from "./app/menu/MenuHeader";
import Spinner from "./app/components/Spinner";
import Menu from "./app/menu/Menu";
import Link from "./app/components/Link";
import JobSelector from "./app/components/JobSelector";
import type { Page } from "./ApplicationState";
import * as Actions from "./actions";
import Calendar from "./app/pages/Calendar";
import Workflow from "./app/pages/Workflow";
import { Started, Stuck, Paused, Finished } from "./app/pages/ExecutionLogs";
import Execution from "./app/pages/Execution";
import type { Statistics } from "./datamodel";

type Props = {
  page: Page,
  projectName: string,
  environment: string,
  workflow: Workflow,
  statistics: Statistics,
  isLoading: boolean,
  classes: any,
  selectedJobs: Array<string>,
  selectJobs: (jobs: Array<string>) => void
};

class App extends React.Component {
  props: Props;

  constructor(props: Props) {
    super(props);
  }

  render() {
    const {
      classes,
      page,
      environment,
      projectName,
      workflow,
      isLoading,
      statistics,
      selectedJobs,
      selectJobs
    } = this.props;

    if (isLoading) {
      return (
        <div className={classes.loading}>
          <Spinner />
        </div>
      );
    } else {
      const renderTab = () => {
        switch (page.id) {
          case "workflow":
            return <Workflow workflow={workflow} job={page.jobId} />;
          case "executions/started":
            return <Started />;
          case "executions/stuck":
            return <Stuck />;
          case "executions/paused":
            return <Paused />;
          case "executions/finished":
            return <Finished />;
          case "executions/detail":
            return <Execution execution={page.execution} />;
          case "timeseries/calendar":
            return <Calendar />;
          default:
            return null;
        }
      };

      return (
        <div className={classes.main}>
          <section className={classes.leftpane}>
            <MenuHeader environment={environment} projectName={projectName} />
            <Menu active={page} statistics={statistics} />
          </section>
          <section className={classes.rightpane}>
            <div className={classes.mainFilter}>
              <JobSelector
                workflow={workflow}
                selected={selectedJobs}
                placeholder={
                  <span>
                    <FilterIcon className={classes.filterIcon} />
                    Filter on specific jobs...
                  </span>
                }
                onChange={selectJobs}
              />
            </div>
            {renderTab()}
          </section>
        </div>
      );
    }
  }
}

let styles = {
  leftpane: {
    width: "300px",
    display: "flex",
    flexDirection: "column",
    backgroundColor: "#2F3647",
    color: "#758390",
    fontFamily: "Arial",
    height: "100vh",
    zIndex: "100"
  },
  rightpane: {
    display: "flex",
    flexGrow: "1",
    alignItems: "stretch",
    flexDirection: "column",
    backgroundColor: "#ECF1F5",
    color: "#3D4454",
    fontFamily: "Arial",
    height: "100vh",
    width: "calc(100vw - 300px)"
  },
  mainFilter: {
    zIndex: "2",
    background: "#fff",
    height: "4em",
    lineHeight: "4em",
    boxShadow: "0px 1px 5px 0px #BECBD6"
  },
  filterIcon: {
    transform: "scale(1.3) translateY(-1.5px) translateX(1px)",
    marginRight: "10px"
  },
  main: {
    backgroundColor: "#ECF1F5",
    display: "flex",
    alignItems: "stretch"
  },
  loading: {
    display: "flex",
    height: "100vh"
  }
};

const mapStateToProps = ({
  page,
  project,
  workflow,
  isLoading,
  statistics,
  selectedJobs
}) => ({
  page,
  projectName: project && project.name,
  environment: "production",
  workflow,
  isLoading,
  statistics,
  selectedJobs
});

const mapDispatchToProps = dispatch => ({
  selectJobs(jobs: Array<string>) {
    dispatch(Actions.selectJobs(jobs));
  }
});

export default connect(mapStateToProps, mapDispatchToProps)(
  injectSheet(styles)(App)
);
